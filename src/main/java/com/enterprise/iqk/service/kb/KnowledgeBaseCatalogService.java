package com.enterprise.iqk.service.kb;

import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.domain.vo.KnowledgeBaseCatalogItemVO;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.enterprise.iqk.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 知识库目录与统计服务（FR-R5-01/02 前端侧支撑）：
 *  - 用户侧目录（GET /ai/knowledge-bases）：普通用户上传/试搜时可选的 ACTIVE 知识库清单，
 *    与智能体目录（AgentCatalogService）同一套可见性语义——只透出展示字段与真实统计；
 *  - 管理侧统计（GET /platform/knowledge-bases/{id}/stats）：文件数/解析中/失败/切片数/
 *    存储大小/最近完成时间全部来自 ingestion_job 真实聚合，无虚构数字。
 * 上传校验（归属校验/ACTIVE 校验）在 IngestionService.submitDocument 内闭环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseCatalogService {

    private final PlatformAssetMapper assetMapper;
    private final IngestionJobMapper ingestionJobMapper;

    /** 用户侧知识库目录：本租户 ACTIVE 知识库，附真实文档统计；坏数据跳过不中断目录。 */
    public List<KnowledgeBaseCatalogItemVO> listUsable(String tenantId) {
        return assetMapper.findActiveByType(TenantContext.normalize(tenantId), "knowledge-bases").stream()
                .map(this::toItem)
                .toList();
    }

    /**
     * 上传目标知识库校验（FR-R5-01）：必须存在、本租户可见且 ACTIVE。
     * 非法值/不存在抛 IllegalArgumentException（控制器层归一为 400）；null 入参返回 null（不归属，原行为）。
     */
    public Long resolveUploadTarget(String tenantId, Long knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId <= 0) {
            return null;
        }
        PlatformAsset kb = assetMapper.findByIdAndTypeVisible(knowledgeBaseId, tenantId, "knowledge-bases");
        if (kb == null) {
            throw new IllegalArgumentException("目标知识库不存在或无权访问");
        }
        if (!"ACTIVE".equalsIgnoreCase(kb.getStatus())) {
            throw new IllegalArgumentException("目标知识库已停用，无法上传");
        }
        return knowledgeBaseId;
    }

    /** 管理侧知识库统计：归属文档聚合 + 磁盘原文件体积（文件缺失跳过）。库不存在返回 404。 */
    public KnowledgeBaseStats stats(String tenantId, Long knowledgeBaseId) {
        PlatformAsset kb = assetMapper.findByIdAndTypeVisible(knowledgeBaseId, TenantContext.normalize(tenantId),
                "knowledge-bases");
        if (kb == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在或无权访问");
        }
        JobStats jobStats = statsByKnowledgeBase(kb.getTenantId(), knowledgeBaseId);
        return KnowledgeBaseStats.builder()
                .knowledgeBaseId(knowledgeBaseId)
                .name(kb.getName())
                .status(kb.getStatus())
                .fileCount(jobStats.fileCount())
                .parsingCount(jobStats.parsingCount())
                .failedCount(jobStats.failedCount())
                .chunkCount(jobStats.chunkCount())
                .storageSize(storageSize(kb.getTenantId(), knowledgeBaseId))
                .lastFinishedAt(jobStats.lastFinishedAt())
                .build();
    }

    /** 目录项转 VO：附该库真实文档统计（失败按空统计降级，目录不因单库统计故障不可用）。 */
    private KnowledgeBaseCatalogItemVO toItem(PlatformAsset asset) {
        JobStats stats;
        try {
            stats = statsByKnowledgeBase(asset.getTenantId(), asset.getId());
        } catch (RuntimeException ex) {
            log.warn("知识库目录统计降级: kbId={}, reason={}", asset.getId(), ex.toString());
            stats = new JobStats(0, 0, 0, 0, null);
        }
        return KnowledgeBaseCatalogItemVO.builder()
                .id(asset.getId())
                .name(asset.getName())
                .description(asset.getDescription())
                .status(asset.getStatus())
                .fileCount(stats.fileCount())
                .chunkCount(stats.chunkCount())
                .build();
    }

    /** 磁盘存储大小：归属文档的最新任务原文件 stat 累加；文件缺失/非法路径按 0 计。 */
    private long storageSize(String tenantId, Long knowledgeBaseId) {
        List<IngestionJob> jobs = ingestionJobMapper.findLatestPerChatByTenant(
                TenantContext.normalize(tenantId), null, 0, 10_000, knowledgeBaseId);
        long total = 0;
        for (IngestionJob job : jobs) {
            if (!StringUtils.hasText(job.getFilePath())) {
                continue;
            }
            try {
                File source = new File(job.getFilePath());
                if (source.isFile()) {
                    total += Files.size(source.toPath());
                }
            } catch (IOException | SecurityException ex) {
                log.debug("存储大小统计跳过文件: jobId={}, reason={}", job.getJobId(), ex.toString());
            }
        }
        return total;
    }

    /**
     * 知识库文档统计（FR-R5-02）：按"最新任务代表文档"口径聚合归属指定知识库的
     * 文件数（= 文档数）、解析中、失败、切片总数与最近完成时间。
     */
    private JobStats statsByKnowledgeBase(String tenantId, Long knowledgeBaseId) {
        Map<String, Object> raw = ingestionJobMapper.statsByKnowledgeBaseRaw(
                TenantContext.normalize(tenantId), knowledgeBaseId);
        if (raw == null || raw.isEmpty()) {
            return new JobStats(0, 0, 0, 0, null);
        }
        return new JobStats(
                longOf(raw.get("file_count")),
                longOf(raw.get("parsing_count")),
                longOf(raw.get("failed_count")),
                longOf(raw.get("chunk_count")),
                toLocalDateTime(raw.get("last_finished_at")));
    }

    /** Map 里的数值列转 long（COUNT/SUM 在不同驱动下可能是 Long/BigInteger/BigDecimal）。 */
    private long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString().trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** MAX(finished_at) 在 Map 里可能是 LocalDateTime 或 Timestamp，统一转 LocalDateTime。 */
    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }

    /** ingestion_job 聚合的原始统计（file/parsing/failed/chunk 计数 + 最近完成时间）。 */
    private record JobStats(long fileCount, long parsingCount, long failedCount,
                            long chunkCount, LocalDateTime lastFinishedAt) {
    }

    /** 管理侧统计视图。 */
    @lombok.Data
    @lombok.Builder
    public static class KnowledgeBaseStats {
        private Long knowledgeBaseId;
        private String name;
        private String status;
        private long fileCount;
        private long parsingCount;
        private long failedCount;
        private long chunkCount;
        private long storageSize;
        private LocalDateTime lastFinishedAt;
    }
}
