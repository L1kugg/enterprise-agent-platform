package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.config.properties.IngestionProperties;
import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.config.properties.VectorStoreProperties;
import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.graph.GraphExtractionService;
import com.enterprise.iqk.ingestion.queue.IngestionQueue;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import com.enterprise.iqk.security.FileSafetyScanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionServiceGraphHookTest {

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
                new SimpleVectorStoreSnapshotPersister(new VectorStoreProperties())
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
    void deleteDocumentByChatCleansGraphWithSameScope() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        GraphExtractionService graphExtractionService = mock(GraphExtractionService.class);
        when(mapper.findLatestByChatId("public", "chat-1", 100)).thenReturn(List.of(job("job-1", "chat-1")));

        IngestionService service = buildService(mapper, mock(VectorStore.class), graphExtractionService);
        List<String> removed = service.deleteDocumentByChat("public", "chat-1");

        assertThat(removed).isEmpty();
        // 图谱按同一 tenant + chatId 作用域联动清理
        verify(graphExtractionService).deleteByChat("public", "chat-1");
        verify(mapper).deleteByChatIdAndTenant("public", "chat-1");
    }

    @Test
    void graphCleanupFailureDoesNotAbortDocumentDeletion() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        GraphExtractionService graphExtractionService = mock(GraphExtractionService.class);
        when(mapper.findLatestByChatId("public", "chat-1", 100)).thenReturn(List.of(job("job-1", "chat-1")));
        doThrow(new RuntimeException("kg down")).when(graphExtractionService).deleteByChat("public", "chat-1");

        IngestionService service = buildService(mapper, mock(VectorStore.class), graphExtractionService);

        assertThatCode(() -> service.deleteDocumentByChat("public", "chat-1"))
                .doesNotThrowAnyException();
        // 清理失败只留孤儿边：任务记录照常删除
        verify(mapper).deleteByChatIdAndTenant("public", "chat-1");
    }

    @Test
    void deleteDocumentByChatWithNoJobsSkipsGraphCleanup() {
        IngestionJobMapper mapper = mock(IngestionJobMapper.class);
        GraphExtractionService graphExtractionService = mock(GraphExtractionService.class);
        when(mapper.findLatestByChatId(anyString(), anyString(), anyInt())).thenReturn(List.of());

        IngestionService service = buildService(mapper, mock(VectorStore.class), graphExtractionService);

        assertThat(service.deleteDocumentByChat("public", "chat-none")).isEmpty();
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

    // 成功钩子（processQueuedJob → submitAsync）依赖真实 PDF 解析，单元层不覆盖；
    // 由发布后的线上验证（传真实 PDF 再查 kg 表）确认。
}
