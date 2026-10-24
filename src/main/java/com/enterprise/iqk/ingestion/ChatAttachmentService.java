package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.security.FileSafetyScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 聊天内临时附件问答（不入知识库）：员工把文件甩进对话，解析纯文本后短存 30 分钟，
 * 附件文本随当次请求注入规划与成稿提示词。与入库链路共享安全扫描与解析组件，
 * 但绝不建向量/关键词索引、不落 IngestionJob——用完即弃的"临时上下文"语义。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAttachmentService {

    /** 注入提示词的最大字符数：超出截断并标记（qwen 系 128k 窗口下留足对话与轨迹余量）。 */
    private static final int MAX_CHARS = 24_000;
    /** 附件有效期：覆盖一次完整对话的追问窗口，过期由存储惰性回收 + 定时清扫。 */
    private static final Duration TTL = Duration.ofMinutes(30);

    private final FileSafetyScanner fileSafetyScanner;
    private final ChatAttachmentStore store;

    /** 解析结果：attachmentId 供聊天请求携带，chars/truncated 供前端 chip 展示。 */
    public record ParsedAttachment(String attachmentId, String filename, int chars, boolean truncated) {
    }

    /** 单条附件的提示词段；无附件返回空串（提示词结构零变化）。 */
    public String attachmentBlock(String tenantId, String attachmentId) {
        if (!StringUtils.hasText(attachmentId)) {
            return "";
        }
        Optional<ChatAttachmentStore.StoredAttachment> found = store.get(tenantId, attachmentId);
        if (found.isEmpty()) {
            return "";
        }
        ChatAttachmentStore.StoredAttachment attachment = found.get();
        return "%n%n用户随本次提问提供的附件（文件《%s》%s），回答应优先依据附件内容：%n%s".formatted(
                attachment.filename(),
                attachment.truncated() ? "内容超长已截断" : "完整内容",
                attachment.text()
        );
    }

    /**
     * 解析上传文件为纯文本并短存：安全扫描（后缀/魔数/病毒特征）→ 按后缀选读取器
     * （PDF 按页，doc/docx/md/txt 走 Tika）→ 字符数上限截断。解析失败抛 IllegalArgumentException。
     */
    public ParsedAttachment parse(String tenantId, String userKey, MultipartFile file) {
        fileSafetyScanner.scan(file);
        String filename = sanitizeFilename(file.getOriginalFilename());
        String text = extractText(file, filename);
        boolean truncated = text.length() > MAX_CHARS;
        String effective = truncated ? text.substring(0, MAX_CHARS) : text;
        if (!StringUtils.hasText(effective)) {
            throw new IllegalArgumentException("未能从文件中提取到文本内容");
        }
        String attachmentId = store.save(new ChatAttachmentStore.StoredAttachment(
                tenantId, userKey, filename, effective, truncated, Instant.now().plus(TTL)));
        log.info("chat attachment parsed: tenant={}, filename={}, chars={}, truncated={}",
                tenantId, filename, effective.length(), truncated);
        return new ParsedAttachment(attachmentId, filename, effective.length(), truncated);
    }

    /** 提取纯文本：不切块不建索引，仅拼接 reader 输出。 */
    private String extractText(MultipartFile file, String filename) {
        List<Document> documents;
        try {
            if (filename.endsWith(".pdf")) {
                documents = new PagePdfDocumentReader(
                        new InputStreamResource(file.getInputStream()),
                        PdfDocumentReaderConfig.builder()
                                .withPageExtractedTextFormatter(ExtractedTextFormatter.defaults())
                                .withPagesPerDocument(1)
                                .build()
                ).read();
            } else {
                documents = new TikaDocumentReader(new InputStreamResource(file.getInputStream())).read();
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("文件读取失败，请重试");
        }
        StringBuilder sb = new StringBuilder();
        for (Document document : documents) {
            String content = document.getText();
            if (StringUtils.hasText(content)) {
                sb.append(content).append('\n');
            }
        }
        return sb.toString();
    }

    private String sanitizeFilename(String originalName) {
        String name = StringUtils.hasText(originalName) ? originalName.trim() : "attachment";
        return name.length() > 128 ? name.substring(name.length() - 128) : name;
    }
}
