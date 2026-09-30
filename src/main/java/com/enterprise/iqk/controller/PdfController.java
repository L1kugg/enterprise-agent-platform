package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.vo.IngestionJobVO;
import com.enterprise.iqk.domain.vo.IngestionSubmitVO;
import com.enterprise.iqk.config.properties.IngestionProperties;
import com.enterprise.iqk.ingestion.IngestionService;
import com.enterprise.iqk.rag.RagAnswerService;
import com.enterprise.iqk.repository.ChatHistoryRepository;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.util.ConversationIdHelper;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * /ai/pdf 入口：PDF 上传入库、原文件下载与 RAG 问答三个端点。
 * 问答走 RagAnswerService 简单版链路，检索按租户 + chatId 双重过滤；
 * chatId 传入检索前做单引号清洗，防过滤表达式注入。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/pdf")
public class PdfController {

    private final IngestionService ingestionService;
    private final ChatHistoryRepository chatHistoryRepository;
    private final RagAnswerService ragAnswerService;
    private final IngestionProperties ingestionProperties;

    /** 上传 PDF 并提交异步入库任务（幂等键可选），返回受理状态与任务详情。 */
    @PostMapping("/upload/{chatId}")
    public IngestionSubmitVO uploadPdf(@PathVariable String chatId,
                                       @RequestParam("file") MultipartFile file,
                                       @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        String tenantId = currentTenantId();
        IngestionJob job = ingestionService.submitPdf(tenantId, chatId, file, idempotencyKey, "");
        chatHistoryRepository.save("pdf", chatId);
        return IngestionSubmitVO.builder()
                .ok(1)
                .msg("accepted")
                .job(IngestionJobVO.builder()
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
                        .build())
                .build();
    }

    /** 下载该会话最近一次入库的原始 PDF，无任务或文件缺失返回 404。 */
    @GetMapping("/file/{chatId}")
    public ResponseEntity<Resource> download(@PathVariable("chatId") String chatId) {
        List<IngestionJob> jobs = ingestionService.listByChatId(currentTenantId(), chatId, 1);
        if (jobs.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "file not found");
        }
        IngestionJob latest = jobs.get(0);
        Resource resource = new FileSystemResource(latest.getFilePath());
        if (!resource.exists()) {
            throw new ResponseStatusException(NOT_FOUND, "file not found");
        }
        String filename = URLEncoder.encode(
                resource.getFilename() == null ? "document.pdf" : resource.getFilename(),
                StandardCharsets.UTF_8
        );
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(resource);
    }

    /** 简单版 RAG 问答：同步计算答案后以 Flux 返回最终文本；docOnly=true 时仅在该文档切片内检索。 */
    @PostMapping(value = "/chat", produces = "text/html;charset=UTF-8")
    public Flux<String> chat(@RequestParam("prompt") String prompt,
                             @RequestParam("chatId") String chatId,
                             @RequestParam(value = "modelProfile", required = false) String modelProfile,
                             @RequestParam(value = "docOnly", required = false, defaultValue = "false") boolean docOnly) {
        String tenantId = currentTenantId();
        chatHistoryRepository.save("pdf", chatId);
        String conversationId = ConversationIdHelper.build("pdf", chatId);
        RagAnswerService.RagResult result = ragAnswerService.answer(
                prompt,
                tenantId,
                sanitize(chatId),
                conversationId,
                modelProfile,
                docOnly
        );
        return Flux.just(result.getAnswer());
    }

    /** 去掉单引号，防止 chatId 拼入向量库过滤表达式造成注入。 */
    private String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace("'", "");
    }

    /** 从 MDC 取归一化租户 ID。 */
    private String currentTenantId() {
        return TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
    }
}
