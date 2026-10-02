package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.config.properties.IngestionProperties;
import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.config.properties.VectorStoreProperties;
import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import com.enterprise.iqk.domain.vo.PagedResult;
import com.enterprise.iqk.graph.GraphExtractionService;
import com.enterprise.iqk.ingestion.queue.IngestionQueue;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import com.enterprise.iqk.security.FileSafetyScanner;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.util.HashUtils;
import com.enterprise.iqk.util.SqlLikeUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 文档入库服务：接收上传（PDF/DOC/DOCX/MD）→ 落盘 + 建任务 → 异步解析/分块/向量化。
 * 幂等键（客户端提供或按内容哈希自动生成）保证同一文件重复提交返回既有任务；
 * 失败按 attemptCount 与 baseDelaySeconds 递增延迟重试，超限转 FAILED 并投递 DLQ；
 * 任务与向量元数据全部携带 tenant_id/chat_id，检索侧按租户隔离。
 */
public class IngestionService {

    private final IngestionJobMapper ingestionJobMapper;
    private final VectorStore vectorStore;
    private final IngestionProperties ingestionProperties;
    private final VectorStoreProperties vectorStoreProperties;
    private final RagProperties ragProperties;
    private final MeterRegistry meterRegistry;
    private final IngestionQueue ingestionQueue;
    private final FileSafetyScanner fileSafetyScanner;
    private final GraphExtractionService graphExtractionService;
    private final SimpleVectorStoreSnapshotPersister snapshotPersister;

    /** 提交文档入库任务（PDF/DOC/DOCX/MD）：安全扫描 → 幂等去重 → 落盘建任务 → 发布队列；重复提交直接返回既有任务。 */
    public IngestionJob submitDocument(String tenantId, String chatId, MultipartFile file, String idempotencyKey, String traceId) {
        String normalizedTenantId = TenantContext.normalize(tenantId);
        if (!StringUtils.hasText(chatId)) {
            throw new IllegalArgumentException("chatId is required");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        fileSafetyScanner.scan(file);

        String sourceName = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "document";
        String sourceType = sourceTypeOf(sourceName);
        String normalizedKey = normalizeIdempotencyKey(normalizedTenantId, chatId, file, idempotencyKey);
        IngestionJob existing = ingestionJobMapper.findByIdempotencyKey(normalizedTenantId, normalizedKey);
        if (existing != null) {
            return existing;
        }

        String jobId = "job-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        String filePath = persistFile(jobId, sourceName, file);
        IngestionJob job = IngestionJob.builder()
                .jobId(jobId)
                .tenantId(normalizedTenantId)
                .chatId(chatId)
                .sourceType(sourceType)
                .sourceName(sourceName)
                .filePath(filePath)
                .idempotencyKey(normalizedKey)
                .status(IngestionJobStatus.PENDING)
                .traceId(traceId)
                .attemptCount(0)
                .maxRetries(ingestionProperties.getMaxRetries())
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            ingestionJobMapper.insert(job);
            if (!"db_polling".equalsIgnoreCase(ingestionProperties.getQueueBackend())) {
                ingestionQueue.publishJob(job.getJobId(), traceId);
            }
            Counter.builder("ingestion.jobs.submitted")
                    .tag("source", sourceType.toLowerCase(Locale.ROOT))
                    .tag("tenant", normalizedTenantId)
                    .register(meterRegistry)
                    .increment();
            return job;
        } catch (DuplicateKeyException duplicateKeyException) {
            IngestionJob concurrent = ingestionJobMapper.findByIdempotencyKey(normalizedTenantId, normalizedKey);
            if (concurrent != null) {
                return concurrent;
            }
            throw duplicateKeyException;
        }
    }

    /** 按租户 + jobId 查询单个任务；jobId 属于其他租户时返回 null。 */
    public IngestionJob getByJobId(String tenantId, String jobId) {
        return ingestionJobMapper.findByJobIdAndTenant(TenantContext.normalize(tenantId), jobId);
    }

    /** 按租户 + chatId 列出最近的入库任务，limit 下限保护为 1。 */
    public List<IngestionJob> listByChatId(String tenantId, String chatId, int limit) {
        return ingestionJobMapper.findLatestByChatId(TenantContext.normalize(tenantId), chatId, Math.max(limit, 1));
    }

    /** 按租户列出最近入库任务（跨 chatId），供控制台知识库页展示，limit 下限保护为 1。 */
    public List<IngestionJob> listRecentByTenant(String tenantId, int limit) {
        return ingestionJobMapper.findLatestByTenant(TenantContext.normalize(tenantId), Math.max(limit, 1));
    }

    /**
     * 知识库文档清单：当前租户内按 chat_id 分组、每组取最新一条任务代表一份文档，
     * 分页 + 按文件名/批次模糊搜索。与管理员总览同一套"最新任务代表文档"的语义，但严格限定本租户。
     */
    public PagedResult<IngestionJob> listDocumentsByTenant(String tenantId, String search, int page, int pageSize) {
        String normalizedTenantId = TenantContext.normalize(tenantId);
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        String keyword = StringUtils.hasText(search) ? SqlLikeUtils.escapeForLike(search.trim()) : null;
        long total = ingestionJobMapper.countLatestPerChatByTenant(normalizedTenantId, keyword);
        if (total == 0) {
            return new PagedResult<>(List.of(), 0, safePage, safePageSize);
        }
        long offset = (long) (safePage - 1) * safePageSize;
        List<IngestionJob> items =
                ingestionJobMapper.findLatestPerChatByTenant(normalizedTenantId, keyword, offset, safePageSize);
        return new PagedResult<>(items, total, safePage, safePageSize);
    }

    /**
     * 删除一个入库批次（文档）：先按 tenant + chat 过滤清掉向量切片，再删磁盘原文件与任务记录。
     * 顺序不能反——向量删除失败直接抛异常中止，保证不出现"任务记录删了、切片还在库里当孤儿"。
     * 返回本次删除的文件名集合（去重）。
     */
    public List<String> deleteDocumentByChat(String tenantId, String chatId) {
        String normalizedTenantId = TenantContext.normalize(tenantId);
        List<IngestionJob> jobs = ingestionJobMapper.findLatestByChatId(normalizedTenantId, chatId, 100);
        if (jobs.isEmpty()) {
            return List.of();
        }
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression scope = builder
                .and(builder.eq("tenant_id", normalizedTenantId), builder.eq("chat_id", chatId))
                .build();
        vectorStore.delete(scope);
        // 图谱数据按同一 chatId 作用域联动清理；失败只留孤儿边不中止文档删除
        // （读路径 getNeighbors 对悬空边已容忍：实体查不到就跳过）。
        try {
            int kgRows = graphExtractionService.deleteByChat(normalizedTenantId, chatId);
            log.info("kg cleanup: tenant={}, chatId={}, rows={}", normalizedTenantId, chatId, kgRows);
        } catch (Exception kgEx) {
            log.warn("kg cleanup 失败（不影响文档删除）: tenant={}, chatId={}, reason={}",
                    normalizedTenantId, chatId, kgEx.toString());
        }
        Set<String> removedFiles = new LinkedHashSet<>();
        for (IngestionJob job : jobs) {
            File source = new File(job.getFilePath());
            if (source.exists() && source.delete()) {
                removedFiles.add(job.getSourceName());
            }
        }
        ingestionJobMapper.deleteByChatIdAndTenant(normalizedTenantId, chatId);
        log.info("ingestion document deleted: tenant={}, chatId={}, jobs={}, files={}",
                normalizedTenantId, chatId, jobs.size(), removedFiles);
        return new ArrayList<>(removedFiles);
    }

    /** 无显式租户的重载：认领租户回退到 MDC（仅适用于 HTTP 请求线程调用）。 */
    public IngestionProcessResult processQueuedJob(String jobId, String traceId) {
        return processQueuedJob(jobId, null, traceId);
    }

    /** 认领并执行一个队列任务：原子认领失败返回 picked=false；成功解析入库，失败按重试计数转 RETRY（带下次执行时间）或 FAILED（投 DLQ）。 */
    public IngestionProcessResult processQueuedJob(String jobId, String tenantId, String traceId) {
        if (!StringUtils.hasText(jobId)) {
            return IngestionProcessResult.builder()
                    .picked(false)
                    .status(IngestionJobStatus.FAILED)
                    .jobId("")
                    .traceId(traceId)
                    .errorMessage("jobId missing")
                    .build();
        }
        // 两条调用路径都必须带租户作用域：
        //  - HTTP 控制器经由基于 MDC 的 TenantContext 过滤器进入这里；
        //  - 后台 worker（Redis / RabbitMQ / db_polling）运行的线程没有 MDC，
        //    必须显式传入任务所属的 tenantId。
        // 回退到 MDC 是为了让既有调用点继续可用，而 SQL 现在会拒绝
        // 认领属于其他租户的任务。
        String claimTenantId = StringUtils.hasText(tenantId)
                ? TenantContext.normalize(tenantId)
                : TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
        LocalDateTime now = LocalDateTime.now();
        int claimed = ingestionJobMapper.claimForRun(jobId, claimTenantId, IngestionJobStatus.RUNNING, now, now);
        if (claimed == 0) {
            return IngestionProcessResult.builder()
                    .picked(false)
                    .status(IngestionJobStatus.RUNNING)
                    .jobId(jobId)
                    .traceId(traceId)
                    .build();
        }

        IngestionJob running = ingestionJobMapper.findByJobId(jobId);
        if (running == null) {
            return IngestionProcessResult.builder()
                    .picked(false)
                    .status(IngestionJobStatus.FAILED)
                    .jobId(jobId)
                    .traceId(traceId)
                    .errorMessage("job not found after claim")
                    .build();
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        String tenantTag = TenantContext.normalize(running.getTenantId());
        try {
            List<Document> chunks = processPdfJob(running);
            ingestionJobMapper.updateTerminalState(
                    running.getJobId(),
                    IngestionJobStatus.SUCCEEDED,
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    null,
                    null
            );
            // 切片数是纯展示字段：回写失败只告警，绝不能把已成功的任务拖进异常分支转 RETRY
            // （那会重新解析入库，向量库里产生重复切片）。
            try {
                ingestionJobMapper.updateChunkCount(running.getJobId(), chunks.size(), LocalDateTime.now());
            } catch (RuntimeException countEx) {
                log.warn("chunk count 回写失败（不影响入库结果）: jobId={}, reason={}",
                        running.getJobId(), countEx.toString());
            }
            // SUCCEEDED 先落库再异步抽图谱：JVM 此刻挂掉最多丢一次图谱，
            // 可用回填端点补，绝不把图谱抽取耦合进入库重试。
            try {
                graphExtractionService.submitAsync(tenantTag, running.getChatId(), running.getJobId(),
                        chunks.stream().map(Document::getText).filter(StringUtils::hasText).toList());
            } catch (Exception kgEx) {
                log.warn("KG 抽取提交失败（不影响入库）: jobId={}, reason={}",
                        running.getJobId(), kgEx.toString());
            }
            sample.stop(Timer.builder("ingestion.jobs.duration")
                    .tag("status", "succeeded")
                    .tag("tenant", tenantTag)
                    .register(meterRegistry));
            Counter.builder("ingestion.jobs.finished")
                    .tag("status", "succeeded")
                    .tag("tenant", tenantTag)
                    .register(meterRegistry)
                    .increment();
            return IngestionProcessResult.builder()
                    .picked(true)
                    .status(IngestionJobStatus.SUCCEEDED)
                    .jobId(running.getJobId())
                    .traceId(traceId)
                    .build();
        } catch (Exception ex) {
            log.error("Failed to process ingestion job: {}", running.getJobId(), ex);
            IngestionJob latest = ingestionJobMapper.findByJobId(running.getJobId());
            int attempts = latest != null && latest.getAttemptCount() != null ? latest.getAttemptCount() : 1;
            int maxRetries = latest != null && latest.getMaxRetries() != null ? latest.getMaxRetries() : ingestionProperties.getMaxRetries();

            IngestionJobStatus nextStatus = attempts < maxRetries ? IngestionJobStatus.RETRY : IngestionJobStatus.FAILED;
            LocalDateTime nextRetryAt = null;
            if (nextStatus == IngestionJobStatus.RETRY) {
                int delaySeconds = ingestionProperties.getBaseDelaySeconds() * Math.max(1, attempts);
                nextRetryAt = LocalDateTime.now().plusSeconds(delaySeconds);
            }
            String error = truncateError(ex.getMessage());
            ingestionJobMapper.updateTerminalState(
                    running.getJobId(),
                    nextStatus,
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    error,
                    nextRetryAt
            );
            sample.stop(Timer.builder("ingestion.jobs.duration")
                    .tag("status", nextStatus.name().toLowerCase())
                    .tag("tenant", tenantTag)
                    .register(meterRegistry));
            Counter.builder("ingestion.jobs.finished")
                    .tag("status", nextStatus.name().toLowerCase())
                    .tag("tenant", tenantTag)
                    .register(meterRegistry)
                    .increment();

            if (nextStatus == IngestionJobStatus.FAILED) {
                ingestionQueue.publishDlq(running.getJobId(), traceId, error);
            }
            return IngestionProcessResult.builder()
                    .picked(true)
                    .status(nextStatus)
                    .jobId(running.getJobId())
                    .traceId(traceId)
                    .errorMessage(error)
                    .build();
        }
    }

    /** 把到期的 RETRY 任务重新发布回队列；db_polling 后端由轮询器消化，直接返回 0。 */
    public int enqueueReadyRetries(int limit) {
        if ("db_polling".equalsIgnoreCase(ingestionProperties.getQueueBackend())) {
            return 0;
        }
        List<IngestionJob> ready = ingestionJobMapper.findReadyRetries(LocalDateTime.now(), Math.max(1, limit));
        int count = 0;
        for (IngestionJob job : ready) {
            int updated = ingestionJobMapper.requeueRetry(job.getJobId(), LocalDateTime.now());
            if (updated > 0) {
                ingestionQueue.publishJob(job.getJobId(), job.getTraceId());
                count++;
            }
        }
        return count;
    }

    /** 解析文档 → token 分块 → 写入向量库 → 视后端情况落快照；返回切片供图谱抽取复用。 */
    private List<Document> processPdfJob(IngestionJob job) {
        List<Document> chunks = parseAndSplit(job);
        vectorStore.add(chunks);
        snapshotPersister.persistIfNeeded(vectorStore);
        return chunks;
    }

    /** 解析文档并按配置切块（不写向量库）；PDF 走专用读取器，doc/docx/md 走 Tika。包内可见供图谱回填服务复用。 */
    List<Document> parseAndSplit(IngestionJob job) {
        File source = new File(job.getFilePath());
        if (!source.exists()) {
            throw new IllegalStateException("source file missing: " + job.getFilePath());
        }
        List<Document> pages;
        if (isPdf(job.getSourceName())) {
            PagePdfDocumentReader reader = new PagePdfDocumentReader(
                    new FileSystemResource(source),
                    PdfDocumentReaderConfig.builder()
                            .withPageExtractedTextFormatter(ExtractedTextFormatter.defaults())
                            .withPagesPerDocument(1)
                            .build()
            );
            pages = reader.read();
        } else {
            pages = new TikaDocumentReader(new FileSystemResource(source)).read();
        }
        return splitDocuments(pages, job);
    }

    /** 文件名是否为 PDF（大小写不敏感）；其余后缀走 Tika 分发。 */
    private boolean isPdf(String sourceName) {
        return sourceName != null && sourceName.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    /** 按文件后缀推导入库来源类型（PDF/DOC/DOCX/MD），未知后缀兜底 OTHER。 */
    private String sourceTypeOf(String sourceName) {
        String lower = sourceName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return "PDF";
        }
        if (lower.endsWith(".doc")) {
            return "DOC";
        }
        if (lower.endsWith(".docx")) {
            return "DOCX";
        }
        if (lower.endsWith(".md")) {
            return "MD";
        }
        return "OTHER";
    }

    /**
     * 按配置的 chunk 参数切块，并为每个 chunk 覆写 tenant_id/chat_id/job_id 等元数据。
     * created_at 写入库时刻的 epoch 毫秒——激活 EvidenceJudge 的时效度维度
     * （此前从不写时间戳，timeliness 恒中性 0.70，新文档的"新鲜度"信息全部丢失）。
     * 包私有以便直接对元数据契约做单元测试（不依赖真实 PDF 解析）。
     */
    List<Document> splitDocuments(List<Document> pages, IngestionJob job) {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(ragProperties.getSplit().getChunkSize())
                .withMinChunkSizeChars(ragProperties.getSplit().getMinChunkSize())
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(ragProperties.getSplit().getMaxNumChunks())
                .withKeepSeparator(true)
                .build();
        List<Document> chunks = splitter.apply(pages);
        long ingestedAt = System.currentTimeMillis();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put("tenant_id", TenantContext.normalize(job.getTenantId()));
            metadata.put("chat_id", job.getChatId());
            metadata.put("job_id", job.getJobId());
            metadata.put("file_name", job.getSourceName());
            metadata.put("source_type", job.getSourceType());
            metadata.put("chunk_index", i);
            metadata.put("created_at", ingestedAt);
            chunk.getMetadata().clear();
            chunk.getMetadata().putAll(metadata);
        }
        return chunks;
    }

    /** 把上传文件以 "jobId_清洗后文件名" 写入存储目录，返回绝对路径。 */
    private String persistFile(String jobId, String sourceName, MultipartFile file) {
        String sanitized = sourceName.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path root = Path.of(ingestionProperties.getStorageDir());
        Path target = root.resolve(jobId + "_" + sanitized);
        try {
            Files.createDirectories(root);
            file.transferTo(target);
            return target.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store uploaded document", e);
        }
    }

    /** 归一幂等键：优先客户端提供值，否则按 tenant|chat|内容哈希自动生成。 */
    private String normalizeIdempotencyKey(String tenantId, String chatId, MultipartFile file, String provided) {
        if (StringUtils.hasText(provided)) {
            return "client:" + provided.trim();
        }
        try {
            String contentHash = HashUtils.sha256Hex(file.getInputStream());
            return "auto:" + HashUtils.sha256Hex(tenantId + "|" + chatId + "|" + contentHash);
        } catch (IOException e) {
            String seed = tenantId + "|" + chatId + "|" + file.getOriginalFilename() + "|" + file.getSize();
            return "auto:" + HashUtils.sha256Hex(seed);
        }
    }

    /** 错误信息截断到 1000 字符，空消息归一为 unknown error。 */
    private String truncateError(String msg) {
        if (!StringUtils.hasText(msg)) {
            return "unknown error";
        }
        return msg.length() <= 1000 ? msg : msg.substring(0, 1000);
    }
}
