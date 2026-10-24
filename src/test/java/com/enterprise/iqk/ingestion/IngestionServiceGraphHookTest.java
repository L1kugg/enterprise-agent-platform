package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.config.properties.IngestionProperties;
import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.config.properties.VectorStoreProperties;
import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.graph.GraphExtractionService;
import com.enterprise.iqk.ingestion.queue.IngestionQueue;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.retrieval.KeywordIndexStore;
import com.enterprise.iqk.security.FileSafetyScanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档批次清理与入库钩子测试：删除链（deleteByChat 图谱/ fact 联动）测 DocumentReplaceService，
 * 解析失败路径测 IngestionService；成功钩子（processQueuedJob → submitAsync）依赖真实 PDF 解析，
 * 单元层不覆盖，由发布后的线上验证确认。
 */
class IngestionServiceGraphHookTest {

    /** 删除链目标已迁至 DocumentReplaceService：直接构造被测对象。 */
    private DocumentReplaceService cleanupService(IngestionJobMapper mapper,
                                                  VectorStore vectorStore,
                                                  GraphExtractionService graphExtractionService,
                                                  MemoryService memoryService) {
        return new DocumentReplaceService(mapper, vectorStore, mock(KeywordIndexStore.class),
                graphExtractionService, memoryService);
    }

    private IngestionService buildService(IngestionJobMapper mapper,
                                          VectorStore vectorStore,
                                          GraphExtractionService graphExtractionService) {
        return new IngestionService(
                mapper,
                vectorStore,
                new IngestionProperties(),
                new VectorStoreProperties(),
                new RagProperties(),
                new SimpleMeterRegistry(),
                mock(IngestionQueue.class),
                mock(FileSafetyScanner.class),
                graphExtractionService,
                mock(com.enterprise.iqk.retrieval.KeywordIndexStore.class),
                new SimpleVectorStoreSnapshotPersister(new VectorStoreProperties()),
                mock(com.enterprise.iqk.service.kb.KnowledgeBaseCatalogService.class),
                mock(com.enterprise.iqk.ingestion.DocumentSegmentService.class),
                mock(com.enterprise.iqk.ingestion.DocumentReplaceService.class)
        );
    }

    private IngestionJob job(String jobId, String chatId) {
        return IngestionJob.builder()
                .jobId(jobId)
                .tenantId("public")
                .chatId(chatId)
                .filePath("/nonexistent/" + jobId + ".pdf")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void deleteByChatCleansGraphWithSameScope() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        GraphExtractionService graphExtractionService = mock(GraphExtractionService.class);
        MemoryService memoryService = mock(MemoryService.class);
        when(mapper.findLatestByChatId("public", "chat-1", 100)).thenReturn(List.of(job("job-1", "chat-1")));

        DocumentReplaceService service = cleanupService(mapper, mock(VectorStore.class),
                graphExtractionService, memoryService);
        List<String> removed = service.deleteByChat("public", "chat-1");

        assertThat(removed).isEmpty();
        // 图谱按同一 tenant + chatId 作用域联动清理
        verify(graphExtractionService).deleteByChat("public", "chat-1");
        // 沉淀自该文档的 fact 记忆按同一作用域级联软失效
        verify(memoryService).invalidateFactsForChat("public", "chat-1");
        verify(mapper).deleteByChatIdAndTenant("public", "chat-1");
    }

    @Test
    void factInvalidationFailureDoesNotAbortDocumentDeletion() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        MemoryService memoryService = mock(MemoryService.class);
        when(mapper.findLatestByChatId("public", "chat-1", 100)).thenReturn(List.of(job("job-1", "chat-1")));
        doThrow(new RuntimeException("db down")).when(memoryService).invalidateFactsForChat("public", "chat-1");

        DocumentReplaceService service = cleanupService(mapper, mock(VectorStore.class),
                mock(GraphExtractionService.class), memoryService);

        assertThatCode(() -> service.deleteByChat("public", "chat-1"))
                .doesNotThrowAnyException();
        // fact 记忆失效失败只留活事实：任务记录照常删除（失效幂等，可重跑补齐）
        verify(mapper).deleteByChatIdAndTenant("public", "chat-1");
    }

    @Test
    void graphCleanupFailureDoesNotAbortDocumentDeletion() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        GraphExtractionService graphExtractionService = mock(GraphExtractionService.class);
        when(mapper.findLatestByChatId("public", "chat-1", 100)).thenReturn(List.of(job("job-1", "chat-1")));
        doThrow(new RuntimeException("kg down")).when(graphExtractionService).deleteByChat("public", "chat-1");

        DocumentReplaceService service = cleanupService(mapper, mock(VectorStore.class), graphExtractionService,
                mock(MemoryService.class));

        assertThatCode(() -> service.deleteByChat("public", "chat-1"))
                .doesNotThrowAnyException();
        // 清理失败只留孤儿边：任务记录照常删除
        verify(mapper).deleteByChatIdAndTenant("public", "chat-1");
    }

    @Test
    void deleteByChatWithNoJobsSkipsGraphCleanup() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        GraphExtractionService graphExtractionService = mock(GraphExtractionService.class);
        when(mapper.findLatestByChatId(anyString(), anyString(), anyInt())).thenReturn(List.of());

        DocumentReplaceService service = cleanupService(mapper, mock(VectorStore.class),
                graphExtractionService, mock(MemoryService.class));

        assertThat(service.deleteByChat("public", "chat-none")).isEmpty();
        verify(graphExtractionService, never()).deleteByChat(anyString(), anyString());
        verify(mapper, never()).deleteByChatIdAndTenant(anyString(), anyString());
    }

    @Test
    void parseAndSplitThrowsWhenSourceFileMissing() {
        IngestionService service = buildService(
                mock(IngestionJobMapper.class), mock(VectorStore.class), mock(GraphExtractionService.class));

        assertThatThrownBy(() -> service.parseAndSplit(job("job-1", "chat-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source file missing");
    }
}
