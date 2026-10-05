package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import com.enterprise.iqk.domain.vo.DocumentContentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档内容预览服务：找最近一次成功入库且磁盘文件还在的任务，重解析原文件取切片文本，
 * 按入库顺序拼成内容块返回。与检索共用同一条解析链路（parseAndSplit），只读、不写向量库；
 * 大文档截断返回，避免一次把几十万字符塞进响应。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentContentService {

    /** 单次返回的正文总字符上限：超出即截断并置 truncated=true。 */
    static final int MAX_TOTAL_CHARS = 200_000;

    private final IngestionService ingestionService;

    /**
     * 读取文档内容预览：同租户内取最近一次 SUCCEEDED 且磁盘文件还在的任务
     * （与图谱回填同一条筛选逻辑），重解析取切片文本，PDF 块附带页码。
     * 找不到可读对象抛 404；解析失败抛 500（中文提示，不泄露堆栈与路径）。
     */
    public DocumentContentVO loadContent(String tenantId, String chatId) {
        IngestionJob target = ingestionService.listByChatId(tenantId, chatId, 100).stream()
                .filter(job -> job.getStatus() == IngestionJobStatus.SUCCEEDED)
                .filter(job -> StringUtils.hasText(job.getFilePath()) && new File(job.getFilePath()).exists())
                .findFirst()
                .orElse(null);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在或尚未成功入库");
        }
        List<Document> chunks;
        try {
            chunks = ingestionService.parseAndSplit(target);
        } catch (Exception ex) {
            // 文件缺失已在上面过滤，这里只剩解析类失败（PDF 损坏/Tika 异常等）
            log.warn("文档内容解析失败: tenant={}, chatId={}, jobId={}, reason={}",
                    tenantId, chatId, target.getJobId(), ex.toString());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文档解析失败，请稍后重试");
        }
        return buildContent(target, chunks);
    }

    /** 切片 → 顺序内容块：跳过空白块，累计超上限即截断；页码取 page_number 元数据。 */
    private DocumentContentVO buildContent(IngestionJob job, List<Document> chunks) {
        List<DocumentContentVO.Block> blocks = new ArrayList<>();
        boolean truncated = false;
        int total = 0;
        for (Document chunk : chunks) {
            String text = chunk.getText() == null ? "" : chunk.getText();
            if (text.isBlank()) {
                continue;
            }
            if (total >= MAX_TOTAL_CHARS) {
                truncated = true;
                break;
            }
            if (total + text.length() > MAX_TOTAL_CHARS) {
                text = text.substring(0, MAX_TOTAL_CHARS - total);
                truncated = true;
            }
            total += text.length();
            blocks.add(DocumentContentVO.Block.builder()
                    .index(blocks.size())
                    .page(pageOf(chunk))
                    .text(text)
                    .build());
        }
        return DocumentContentVO.builder()
                .chatId(job.getChatId())
                .sourceName(job.getSourceName())
                .sourceType(job.getSourceType())
                .chunkCount(blocks.size())
                .truncated(truncated)
                .blocks(blocks)
                .build();
    }

    /** PDF 切片携带的页码元数据（PagePdfDocumentReader 写入 page_number），缺失或非数字返回 null。 */
    private Integer pageOf(Document chunk) {
        Object page = chunk.getMetadata().get("page_number");
        return page instanceof Number number ? number.intValue() : null;
    }
}
