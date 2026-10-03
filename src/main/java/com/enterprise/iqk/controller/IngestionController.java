package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.vo.IngestionDocumentVO;
import com.enterprise.iqk.domain.vo.IngestionJobVO;
import com.enterprise.iqk.domain.vo.IngestionSubmitVO;
import com.enterprise.iqk.domain.vo.PagedResult;
import com.enterprise.iqk.retrieval.RetrievalPreviewResult;
import com.enterprise.iqk.retrieval.RetrievalPreviewService;
import com.enterprise.iqk.config.properties.IngestionProperties;
import com.enterprise.iqk.ingestion.DocumentGraphBackfillService;
import com.enterprise.iqk.ingestion.IngestionProcessResult;
import com.enterprise.iqk.ingestion.IngestionService;
import com.enterprise.iqk.repository.ChatHistoryRepository;
import com.enterprise.iqk.security.TenantContext;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ingestion")
@RequiredArgsConstructor
/**
 * 文档入库 HTTP 入口：上传 PDF、查询任务状态、手动触发处理。
 * 全部端点以 MDC 中的租户为作用域（跨租户任务不可见）；process 端点额外要求 ADMIN 角色。
 */
public class IngestionController {

    private final IngestionService ingestionService;
    private final DocumentGraphBackfillService documentGraphBackfillService;
    private final ChatHistoryRepository chatHistoryRepository;
    private final ObjectProvider<Tracer> tracerProvider;
    private final IngestionProperties ingestionProperties;
    private final RetrievalPreviewService retrievalPreviewService;

    @PostMapping("/upload/{chatId}")
    /** 上传文档（PDF/DOC/DOCX/MD）创建入库任务（支持幂等键去重），同时登记 pdf 会话历史。 */
    public IngestionSubmitVO uploadDocument(@PathVariable String chatId,
                                            @RequestParam("file") MultipartFile file,
                                            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        String traceId = currentTraceId();
        IngestionJob job = ingestionService.submitDocument(currentTenantId(), chatId, file, idempotencyKey, traceId);
        chatHistoryRepository.save("pdf", chatId);
        return IngestionSubmitVO.builder()
                .ok(1)
                .msg("accepted")
                .job(toVO(job))
                .build();
    }

    @GetMapping("/jobs/{jobId}")
    /** 查询单个任务详情；不存在或属于其他租户返回 404。 */
    public IngestionJobVO getJob(@PathVariable String jobId) {
        IngestionJob job = ingestionService.getByJobId(currentTenantId(), jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found");
        }
        return toVO(job);
    }

    @GetMapping("/jobs")
    /** 按 chatId 列出当前租户最近的入库任务，limit 夹取在 1-100。 */
    public List<IngestionJobVO> getJobsByChatId(@RequestParam("chatId") String chatId,
                                                @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ingestionService.listByChatId(currentTenantId(), chatId, Math.max(1, Math.min(limit, 100)))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @GetMapping("/jobs/recent")
    /** 按租户列出最近入库任务（跨 chatId），供控制台知识库页展示，limit 夹取在 1-100。 */
    public List<IngestionJobVO> getRecentJobs(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ingestionService.listRecentByTenant(currentTenantId(), Math.max(1, Math.min(limit, 100)))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @GetMapping("/documents")
    /** 知识库文档清单：本租户内按 chat 分组的文档（每份取最新任务），分页 + 按文件名/批次搜索。 */
    public PagedResult<IngestionDocumentVO> listDocuments(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "search", required = false) String search) {
        PagedResult<IngestionJob> result = ingestionService.listDocumentsByTenant(
                currentTenantId(), search, page, pageSize);
        List<IngestionDocumentVO> items = result.getItems().stream().map(this::toDocumentVO).toList();
        return new PagedResult<>(items, result.getTotal(), result.getPage(), result.getPageSize());
    }

    @GetMapping("/search")
    /** 知识库试搜：只走向量/关键词/图谱三条本地检索路返回原始命中，不调 LLM、不出答案。 */
    public RetrievalPreviewResult previewSearch(
            @RequestParam("q") String query,
            @RequestParam(value = "topK", defaultValue = "6") int topK) {
        if (!StringUtils.hasText(query) || !StringUtils.hasText(query.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query q is required");
        }
        return retrievalPreviewService.search(currentTenantId(), query.trim(), topK);
    }

    @DeleteMapping("/documents/{chatId}")
    /** 删除一个入库批次（文档）：清向量切片 → 删原文件 → 删任务记录，任一失败即中止。
     * 严格限定本租户（MDC 租户作用域），本租户内任何已认证用户可删自己上传的文档。 */
    public Map<String, Object> deleteDocument(@PathVariable String chatId) {
        List<String> removedFiles = ingestionService.deleteDocumentByChat(currentTenantId(), chatId);
        return Map.of(
                "ok", 1,
                "msg", removedFiles.isEmpty() ? "没有找到可删除的文档" : "已删除：" + String.join(", ", removedFiles)
        );
    }

    @PostMapping("/documents/{chatId}/graph/build")
    /** 存量文档补图谱：同步重解析磁盘 PDF 并抽取实体/关系/事实（任意已认证用户，仅限本租户文档）。 */
    public Map<String, Object> buildDocumentGraph(@PathVariable String chatId) {
        var rebuilt = documentGraphBackfillService.rebuildForChat(currentTenantId(), chatId);
        if (rebuilt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no succeeded document to rebuild for chat: " + chatId);
        }
        var result = rebuilt.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", 1);
        body.put("chatId", chatId);
        body.put("entities", result.entityCount());
        body.put("relations", result.relationCount());
        body.put("facts", result.factCount());
        body.put("skipped", result.skipReason());
        return body;
    }

    @PostMapping("/jobs/process")
    @PreAuthorize("hasRole('ADMIN')")
    /** ADMIN 手动触发：带 jobId 时校验租户归属后同步处理一个任务，不带则批量重入队到期重试。 */
    public IngestionSubmitVO processOne(@RequestParam(value = "jobId", required = false) String jobId) {
        if (!StringUtils.hasText(jobId)) {
            int enqueued = ingestionService.enqueueReadyRetries(20);
            return IngestionSubmitVO.builder()
                    .ok(1)
                    .msg("requeue=" + enqueued)
                    .job(null)
                    .build();
        }
        IngestionJob scopedJob = ingestionService.getByJobId(currentTenantId(), jobId);
        if (scopedJob == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found");
        }
        IngestionProcessResult processed = ingestionService.processQueuedJob(jobId, currentTraceId());
        boolean picked = processed.isPicked();
        return IngestionSubmitVO.builder()
                .ok(1)
                .msg(picked ? "processed" : "empty")
                .job(null)
                .build();
    }

    /** 领域对象转文档清单视图：附带切片数与磁盘文件大小（缺失为 null，不影响列表）。 */
    private IngestionDocumentVO toDocumentVO(IngestionJob job) {
        return IngestionDocumentVO.builder()
                .chatId(job.getChatId())
                .sourceName(job.getSourceName())
                .sourceType(job.getSourceType())
                .status(job.getStatus())
                .chunkCount(job.getChunkCount())
                .fileSize(resolveFileSize(job.getFilePath()))
                .createdAt(job.getCreatedAt())
                .finishedAt(job.getFinishedAt())
                .build();
    }

    /** 磁盘 stat 最新任务的 file_path；文件缺失/路径非法时返回 null，不影响列表。 */
    private static Long resolveFileSize(String filePath) {
        if (!StringUtils.hasText(filePath)) {
            return null;
        }
        try {
            return Files.size(Path.of(filePath));
        } catch (IOException | SecurityException | InvalidPathException ex) {
            return null;
        }
    }

    /** 领域对象转视图对象，附带当前队列后端标识。 */
    private IngestionJobVO toVO(IngestionJob job) {
        return IngestionJobVO.builder()
                .jobId(job.getJobId())
                .chatId(job.getChatId())
                .sourceName(job.getSourceName())
                .status(job.getStatus())
                .attemptCount(job.getAttemptCount())
                .maxRetries(job.getMaxRetries())
                .errorMessage(job.getErrorMessage())
                .traceId(job.getTraceId())
                .queueBackend(ingestionProperties.getQueueBackend())
                .createdAt(job.getCreatedAt())
                .startedAt(job.getStartedAt())
                .finishedAt(job.getFinishedAt())
                .build();
    }

    /** 取当前链路 traceId；无 Tracer/Span 时返回空串。 */
    private String currentTraceId() {
        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer == null) {
            return "";
        }
        var span = tracer.currentSpan();
        if (span == null) {
            return "";
        }
        String traceId = span.context().traceId();
        return StringUtils.hasText(traceId) ? traceId : "";
    }

    /** 从 MDC 取当前租户并归一化。 */
    private String currentTenantId() {
        return TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
    }
}
