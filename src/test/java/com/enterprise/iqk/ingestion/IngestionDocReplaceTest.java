package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.config.properties.IngestionProperties;
import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.config.properties.VectorStoreProperties;
import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import com.enterprise.iqk.ingestion.queue.IngestionQueue;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.retrieval.KeywordIndexStore;
import com.enterprise.iqk.security.FileSafetyScanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 知识库同名文档替换（后写胜）：新批次成功后旧批次退役——向量清理 + SUPERSEDED 标记。 */
class IngestionDocReplaceTest {

    private IngestionJobMapper mapper;
    private VectorStore vectorStore;
    private KeywordIndexStore keywordIndexStore;
    private DocumentReplaceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(IngestionJobMapper.class);
        vectorStore = mock(VectorStore.class);
        keywordIndexStore = mock(KeywordIndexStore.class);
        service = new DocumentReplaceService(
                mapper,
                vectorStore,
                keywordIndexStore,
                mock(com.enterprise.iqk.graph.GraphExtractionService.class),
                mock(MemoryService.class)
        );
    }

    private IngestionJob job(Long kbId, String name, String chatId) {
        return IngestionJob.builder()
                .jobId("job-" + chatId)
                .tenantId("public")
                .chatId(chatId)
                .sourceName(name)
                .sourceType("MD")
                .filePath("./data/uploads/missing-file.md")
                .knowledgeBaseId(kbId)
                .status(IngestionJobStatus.SUCCEEDED)
                .build();
    }

    @Test
    void replacesOldSameNameBatchesInSameKnowledgeBase() {
        IngestionJob newJob = job(34L, "退换货政策.md", "doc-new");
        IngestionJob oldJob = job(34L, "退换货政策.md", "doc-old");
        when(mapper.findReplaceCandidates("public", 34L, "退换货政策.md", "doc-new"))
                .thenReturn(List.of(oldJob));

        service.retireSupersededBatches(newJob);

        verify(vectorStore).delete(org.mockito.ArgumentMatchers.<org.springframework.ai.vectorstore.filter.Filter.Expression>any());
        verify(keywordIndexStore).deleteByChat("public", "doc-old");
        verify(mapper).markSupersededByChat(eq("public"), eq("doc-old"), any(LocalDateTime.class));
    }

    @Test
    void nonKbUploadNeverTriggersReplacement() {
        IngestionJob chatUpload = job(null, "notes.md", "doc-1");

        service.retireSupersededBatches(chatUpload);

        verify(mapper, never()).findReplaceCandidates(anyString(), anyLong(), anyString(), anyString());
        verify(vectorStore, never()).delete(org.mockito.ArgumentMatchers.<org.springframework.ai.vectorstore.filter.Filter.Expression>any());
    }

    @Test
    void singleBatchFailureDoesNotBlockOtherRetirements() {
        IngestionJob newJob = job(34L, "政策.md", "doc-new");
        IngestionJob first = job(34L, "政策.md", "doc-a");
        IngestionJob second = job(34L, "政策.md", "doc-b");
        when(mapper.findReplaceCandidates("public", 34L, "政策.md", "doc-new"))
                .thenReturn(List.of(first, second));
        // 第一批向量删除失败 → 该批跳过，第二批照常退役
        when(mapper.findLatestByChatId(eq("public"), eq("doc-b"), eq(100)))
                .thenReturn(List.of(second));
        org.mockito.Mockito.doThrow(new RuntimeException("vec down"))
                .doNothing()
                .when(vectorStore)
                .delete(org.mockito.ArgumentMatchers.<org.springframework.ai.vectorstore.filter.Filter.Expression>any());

        service.retireSupersededBatches(newJob);

        verify(mapper, never()).markSupersededByChat(eq("public"), eq("doc-a"), any(LocalDateTime.class));
        verify(mapper).markSupersededByChat(eq("public"), eq("doc-b"), any(LocalDateTime.class));
        verify(keywordIndexStore, never()).deleteByChat(eq("public"), eq("doc-a"));
        verify(keywordIndexStore).deleteByChat("public", "doc-b");
    }
}
