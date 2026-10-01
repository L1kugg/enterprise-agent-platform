package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import com.enterprise.iqk.graph.GraphExtractionService;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import com.enterprise.iqk.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * 存量文档图谱回填：从磁盘重解析已入库成功的 PDF，取切片文本走同步图谱抽取。
 * 与入库自动钩子共用 extractAndStore（写前删，重复回填不翻倍）；不受 extraction-enabled
 * 开关限制——开关只管"新文档入库自动抽"，回填是用户显式发起的动作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentGraphBackfillService {

    private final IngestionJobMapper ingestionJobMapper;
    private final IngestionService ingestionService;
    private final GraphExtractionService graphExtractionService;

    /**
     * 重建一个 chat 的图谱：找最近一份 SUCCEEDED 且磁盘文件还在的任务，
     * 重解析取切片文本后同步抽取（一次 economy 调用，约 5-30 秒）。
     * 找不到可回填对象返回 empty（调用方转 404）。
     */
    public Optional<GraphExtractionService.GraphExtractionResult> rebuildForChat(String tenantId, String chatId) {
        String normalizedTenantId = TenantContext.normalize(tenantId);
        IngestionJob target = ingestionJobMapper
                .findLatestByChatId(normalizedTenantId, chatId, 100)
                .stream()
                .filter(job -> job.getStatus() == IngestionJobStatus.SUCCEEDED)
                .filter(job -> StringUtils.hasText(job.getFilePath()) && new File(job.getFilePath()).exists())
                .findFirst()
                .orElse(null);
        if (target == null) {
            return Optional.empty();
        }
        List<Document> chunks = ingestionService.parseAndSplit(target);
        List<String> texts = chunks.stream()
                .map(Document::getText)
                .filter(StringUtils::hasText)
                .toList();
        GraphExtractionService.GraphExtractionResult result =
                graphExtractionService.extractAndStore(normalizedTenantId, chatId, target.getJobId(), texts);
        log.info("kg backfill: tenant={}, chatId={}, jobId={}, entities={}, relations={}, facts={}, skip={}",
                normalizedTenantId, chatId, target.getJobId(),
                result.entityCount(), result.relationCount(), result.factCount(), result.skipReason());
        return Optional.of(result);
    }
}
