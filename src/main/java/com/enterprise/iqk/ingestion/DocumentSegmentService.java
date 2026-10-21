package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.domain.DocumentSegmentRecord;
import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.mapper.DocumentSegmentMapper;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import com.enterprise.iqk.retrieval.KeywordIndexStore;
import com.enterprise.iqk.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档分段治理（R8，FR-R8-04）：基于 document_segment 真实数据面做
 * 查看 / 编辑（单段重嵌入）/ 删除 / 合并 / 拆分 / 整篇重建向量。
 * 向量与关键词索引的对应键 = 切片元数据 job_id + chunk_index（与 document_segment 一一对应）。
 * 结构性操作（合并/拆分/整篇重建）走"文档级重建"：DB 重排后删全部向量重嵌，
 * 上限 200 段防长文档同步嵌入拖垮请求；单段编辑/删除走精确向量删除+重嵌，代价 O(1)。
 * 全部操作严格 tenant 圈定；嵌入失败置 vector_synced=0 留痕不静默。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentSegmentService {

    /** 文档级重建的段数上限：超过则拒绝（防大文档同步嵌入拖垮 HTTP 请求）。 */
    private static final int REBUILD_MAX_SEGMENTS = 200;
    /** 拆分产出上限。 */
    private static final int SPLIT_MAX_PARTS = 20;

    private final DocumentSegmentMapper segmentMapper;
    private final IngestionJobMapper ingestionJobMapper;
    private final VectorStore vectorStore;
    private final KeywordIndexStore keywordIndexStore;
    private final com.enterprise.iqk.service.kb.KnowledgeBaseCatalogService knowledgeBaseCatalogService;
    private final com.enterprise.iqk.config.properties.RagProperties ragProperties;
    private final com.enterprise.iqk.ingestion.queue.IngestionQueue ingestionQueue;
    private final com.enterprise.iqk.config.properties.IngestionProperties ingestionProperties;

    // ---------- 入库侧（IngestionService 委托） ----------

    /**
     * 分段快照落库（R8）：按 job 覆盖式写入 document_segment——重入库幂等。
     * 与向量切片一一对应（job_id + chunk_index），是分段治理的数据面。
     * 失败不回滚入库（向量/关键词已是事实源），治理功能对该文档降级。
     */
    public void persistSegments(IngestionJob job, List<Document> chunks) {
        String tenantId = TenantContext.normalize(job.getTenantId());
        try {
            segmentMapper.deleteByJob(tenantId, job.getJobId());
            LocalDateTime now = LocalDateTime.now();
            for (int i = 0; i < chunks.size(); i++) {
                segmentMapper.insert(DocumentSegmentRecord.builder()
                        .tenantId(tenantId)
                        .chatId(job.getChatId())
                        .jobId(job.getJobId())
                        .segIndex(i)
                        .content(chunks.get(i).getText() == null ? "" : chunks.get(i).getText())
                        .vectorSynced(true)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
            }
        } catch (RuntimeException ex) {
            log.warn("分段快照落库失败（不影响入库）: jobId={}, reason={}", job.getJobId(), ex.toString());
        }
    }

    /** 生效切分参数（FR-R8-03）：归属知识库配置了 split 则覆盖全局默认。 */
    public com.enterprise.iqk.config.properties.RagProperties.Split resolveSplit(IngestionJob job) {
        com.enterprise.iqk.config.properties.RagProperties.Split fallback = ragProperties.getSplit();
        if (job.getKnowledgeBaseId() == null) {
            return fallback;
        }
        try {
            return knowledgeBaseCatalogService
                    .findSplitConfig(TenantContext.normalize(job.getTenantId()), job.getKnowledgeBaseId())
                    .map(config -> {
                        var override = new com.enterprise.iqk.config.properties.RagProperties.Split();
                        override.setChunkSize(config.chunkSize());
                        override.setMinChunkSize(config.minChunkSize());
                        override.setMaxNumChunks(config.maxNumChunks());
                        return override;
                    })
                    .orElse(fallback);
        } catch (RuntimeException ex) {
            log.warn("知识库切分配置读取失败，回退全局默认: kbId={}, reason={}",
                    job.getKnowledgeBaseId(), ex.toString());
            return fallback;
        }
    }

    /** 失败文档重试（FR-R8-02）：文档内 FAILED 任务重置 PENDING 并重新入队，返回重置数。 */
    public int retryFailedByChat(String tenantId, String chatId) {
        String normalizedTenantId = TenantContext.normalize(tenantId);
        List<IngestionJob> failed = ingestionJobMapper.findFailedByChat(normalizedTenantId, chatId);
        int retried = 0;
        for (IngestionJob job : failed) {
            if (ingestionJobMapper.retryFailedJob(job.getJobId(), LocalDateTime.now()) > 0) {
                if (!"db_polling".equalsIgnoreCase(ingestionProperties.getQueueBackend())) {
                    ingestionQueue.publishJob(job.getJobId(), job.getTraceId());
                }
                retried++;
            }
        }
        return retried;
    }

    /** 按文档名找最新文档（聊天引用点击跳转定位 chatId），不存在返回 null。 */
    public IngestionJob lookupBySourceName(String tenantId, String sourceName) {
        return ingestionJobMapper.findLatestBySourceName(TenantContext.normalize(tenantId), sourceName);
    }

    // ---------- 治理操作 ----------

    /** 分段分页（按 seg_index 升序）。 */
    public Map<String, Object> page(String tenantId, String chatId, int page, int pageSize) {
        requireChatId(chatId);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        long total = segmentMapper.countByChat(tenantId, chatId);
        List<DocumentSegmentRecord> items = total == 0 ? List.of()
                : segmentMapper.pageByChat(tenantId, chatId, (long) (safePage - 1) * safeSize, safeSize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return result;
    }

    /** 编辑分段内容：更新 DB → 删旧向量 → 重嵌新文本 → 关键词 upsert → 同步标记。 */
    public DocumentSegmentRecord updateContent(String tenantId, Long id, String content) {
        DocumentSegmentRecord segment = requireSegment(tenantId, id);
        if (!StringUtils.hasText(content) || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分段内容不能为空");
        }
        IngestionJob job = requireLatestJob(tenantId, segment.getChatId());
        segmentMapper.updateById(DocumentSegmentRecord.builder()
                .id(segment.getId())
                .content(content)
                .vectorSynced(false)
                .updatedAt(LocalDateTime.now())
                .build());
        Document document = toDocument(tenantId, segment.getChatId(), segment.getJobId(),
                segment.getSegIndex(), content, job);
        deleteSegmentVector(tenantId, segment.getJobId(), segment.getSegIndex());
        vectorStore.add(List.of(document));
        keywordIndexStore.indexAll(tenantId, List.of(document));
        markSynced(segment.getId());
        log.info("分段已编辑并重嵌入: tenant={}, segmentId={}, index={}",
                tenantId, id, segment.getSegIndex());
        return requireSegment(tenantId, id);
    }

    /** 删除分段：向量 + 关键词行 + DB 行一起删（段序留空洞，展示按剩余索引排序）。 */
    public void delete(String tenantId, Long id) {
        DocumentSegmentRecord segment = requireSegment(tenantId, id);
        deleteSegmentVector(tenantId, segment.getJobId(), segment.getSegIndex());
        keywordIndexStore.deleteByJobChunkIndex(tenantId, segment.getJobId(), segment.getSegIndex());
        segmentMapper.deleteById(segment.getId());
        log.info("分段已删除: tenant={}, segmentId={}, index={}", tenantId, id, segment.getSegIndex());
    }

    /** 合并相邻分段：id 与 targetId 必须同文档且段序相邻，内容拼接后重嵌入，后一段删除。 */
    public DocumentSegmentRecord merge(String tenantId, Long id, Long targetId) {
        DocumentSegmentRecord first = requireSegment(tenantId, id);
        DocumentSegmentRecord second = requireSegment(tenantId, targetId);
        if (!first.getChatId().equals(second.getChatId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "两个分段不属于同一文档");
        }
        if (Math.abs(first.getSegIndex() - second.getSegIndex()) != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能合并段序相邻的分段");
        }
        DocumentSegmentRecord earlier = first.getSegIndex() < second.getSegIndex() ? first : second;
        DocumentSegmentRecord later = earlier == first ? second : first;
        String mergedContent = earlier.getContent() + "\n\n" + later.getContent();
        DocumentSegmentRecord updated = updateContent(tenantId, earlier.getId(), mergedContent);
        delete(tenantId, later.getId());
        return updated;
    }

    /**
     * 拆分分段：按分隔符（正则字面量）或固定长度拆为 2..20 段。
     * 原段保留首段并重嵌入，其余按新段序追加到文档末尾（拆分后顺序重排在 v1 以追加实现）。
     */
    public List<DocumentSegmentRecord> split(String tenantId, Long id, String separator, Integer length) {
        DocumentSegmentRecord segment = requireSegment(tenantId, id);
        IngestionJob job = requireLatestJob(tenantId, segment.getChatId());
        List<String> parts = splitContent(segment.getContent(), separator, length);
        // 追加起点：当前文档最大段序 + 1
        int nextIndex = segmentMapper.maxSegIndex(tenantId, segment.getChatId()) + 1;
        List<DocumentSegmentRecord> created = new ArrayList<>();
        segmentMapper.updateById(DocumentSegmentRecord.builder()
                .id(segment.getId())
                .content(parts.get(0))
                .vectorSynced(false)
                .updatedAt(LocalDateTime.now())
                .build());
        deleteSegmentVector(tenantId, segment.getJobId(), segment.getSegIndex());
        vectorStore.add(List.of(toDocument(tenantId, segment.getChatId(), segment.getJobId(),
                segment.getSegIndex(), parts.get(0), job)));
        keywordIndexStore.indexAll(tenantId, List.of(toDocument(tenantId, segment.getChatId(),
                segment.getJobId(), segment.getSegIndex(), parts.get(0), job)));
        markSynced(segment.getId());
        created.add(requireSegment(tenantId, id));
        for (int i = 1; i < parts.size(); i++) {
            int index = nextIndex + i - 1;
            DocumentSegmentRecord row = DocumentSegmentRecord.builder()
                    .tenantId(tenantId)
                    .chatId(segment.getChatId())
                    .jobId(segment.getJobId())
                    .segIndex(index)
                    .content(parts.get(i))
                    .vectorSynced(false)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            segmentMapper.insert(row);
            vectorStore.add(List.of(toDocument(tenantId, segment.getChatId(), segment.getJobId(),
                    index, parts.get(i), job)));
            keywordIndexStore.indexAll(tenantId, List.of(toDocument(tenantId, segment.getChatId(),
                    segment.getJobId(), index, parts.get(i), job)));
            row.setVectorSynced(true);
            created.add(row);
        }
        log.info("分段已拆分: tenant={}, segmentId={}, parts={}", tenantId, id, parts.size());
        return created;
    }

    /**
     * 整篇重建向量（FR-R8-04"重建向量"）：按 DB 分段快照删除全部旧向量后重嵌，
     * 关键词索引整篇重写。段数上限 {@value REBUILD_MAX_SEGMENTS}。
     */
    public Map<String, Object> rebuildDocument(String tenantId, String chatId) {
        requireChatId(chatId);
        IngestionJob job = requireLatestJob(tenantId, chatId);
        List<DocumentSegmentRecord> segments = segmentMapper.findByChat(tenantId, chatId);
        if (segments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该文档没有分段数据（可能是历史文档），请重新上传或先执行一次入库");
        }
        if (segments.size() > REBUILD_MAX_SEGMENTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "分段数超过重建上限 " + REBUILD_MAX_SEGMENTS + "，请拆分为多个文档");
        }
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        vectorStore.delete(builder
                .and(builder.eq("tenant_id", tenantId), builder.eq("chat_id", chatId))
                .build());
        List<Document> documents = new ArrayList<>();
        for (DocumentSegmentRecord segment : segments) {
            documents.add(toDocument(tenantId, chatId, segment.getJobId(),
                    segment.getSegIndex(), segment.getContent(), job));
        }
        vectorStore.add(documents);
        keywordIndexStore.deleteByChat(tenantId, chatId);
        keywordIndexStore.indexAll(tenantId, documents);
        LocalDateTime now = LocalDateTime.now();
        segments.forEach(segment -> segmentMapper.updateById(DocumentSegmentRecord.builder()
                .id(segment.getId())
                .vectorSynced(true)
                .updatedAt(now)
                .build()));
        log.info("文档向量已重建: tenant={}, chatId={}, segments={}", tenantId, chatId, segments.size());
        return Map.of("chatId", chatId, "segments", segments.size(), "status", "rebuilt");
    }

    /** 由分段重建向量库 Document（元数据与入库时同构）。 */
    private Document toDocument(String tenantId, String chatId, String jobId,
                                int segIndex, String content, IngestionJob job) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenant_id", tenantId);
        metadata.put("chat_id", chatId);
        metadata.put("job_id", jobId);
        metadata.put("file_name", job.getSourceName());
        metadata.put("source_type", job.getSourceType());
        metadata.put("chunk_index", segIndex);
        metadata.put("created_at", System.currentTimeMillis());
        if (job.getKnowledgeBaseId() != null) {
            metadata.put("knowledge_base_id", job.getKnowledgeBaseId());
        }
        return Document.builder().text(content).metadata(metadata).build();
    }

    /** 单段向量删除：tenant + job_id + chunk_index 三键精确定位。 */
    private void deleteSegmentVector(String tenantId, String jobId, int segIndex) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        vectorStore.delete(builder.and(
                builder.eq("tenant_id", tenantId),
                builder.and(builder.eq("job_id", jobId), builder.eq("chunk_index", segIndex)))
                .build());
    }

    private void markSynced(Long id) {
        segmentMapper.updateById(DocumentSegmentRecord.builder()
                .id(id)
                .vectorSynced(true)
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private DocumentSegmentRecord requireSegment(String tenantId, Long id) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid segment id");
        }
        DocumentSegmentRecord segment = segmentMapper.selectById(id);
        if (segment == null || !tenantId.equals(segment.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分段不存在");
        }
        return segment;
    }

    private IngestionJob requireLatestJob(String tenantId, String chatId) {
        List<IngestionJob> jobs = ingestionJobMapper.findLatestByChatId(tenantId, chatId, 1);
        if (jobs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
        return jobs.get(0);
    }

    private void requireChatId(String chatId) {
        if (!StringUtils.hasText(chatId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "chatId is required");
        }
    }

    /** 拆分策略：separator 优先（字面量切分），否则按固定长度切；产出 2..20 段且每段非空。 */
    private List<String> splitContent(String content, String separator, Integer length) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(separator)) {
            for (String part : content.split(java.util.regex.Pattern.quote(separator))) {
                if (StringUtils.hasText(part)) {
                    parts.add(part.trim());
                }
            }
        } else if (length != null && length > 0) {
            for (int i = 0; i < content.length(); i += length) {
                String part = content.substring(i, Math.min(content.length(), i + length)).trim();
                if (StringUtils.hasText(part)) {
                    parts.add(part);
                }
            }
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "请提供分隔符或分段长度");
        }
        if (parts.size() < 2 || parts.size() > SPLIT_MAX_PARTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "拆分需产出 2~" + SPLIT_MAX_PARTS + " 个非空分段，当前：" + parts.size());
        }
        return parts;
    }
}
