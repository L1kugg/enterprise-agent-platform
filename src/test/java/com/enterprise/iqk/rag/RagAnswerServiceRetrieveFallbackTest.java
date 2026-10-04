package com.enterprise.iqk.rag;

import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.service.TenantCostService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 泛问兜底回归：严格阈值为空时放宽到最近邻重试一次；兜底捞回按绝对相似度剔除完全无关结果。 */
class RagAnswerServiceRetrieveFallbackTest {

    private RagAnswerService service(VectorStore vectorStore) {
        return new RagAnswerService(vectorStore, mock(ChatClient.class),
                mock(ModelRouter.class), new RagProperties(), new SimpleMeterRegistry(),
                mock(TenantCostService.class));
    }

    @Test
    void strictHitSkipsRelaxedRetry() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(Document.builder().text("切片").build()));

        List<Document> docs = service(vectorStore).retrieveWithRelaxedFallback("HashMap 原理", "tenant_id == \"t\"");

        assertThat(docs).hasSize(1);
        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void strictEmptyRetriesWithAcceptAllThreshold() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        List<Document> docs = service(vectorStore).retrieveWithRelaxedFallback("这份文档讲了什么", "tenant_id == \"t\"");

        assertThat(docs).isEmpty();
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, times(2)).similaritySearch(captor.capture());
        assertThat(captor.getAllValues().get(0).getSimilarityThreshold())
                .isEqualTo(new RagProperties().getSimilarityThreshold());
        assertThat(captor.getAllValues().get(1).getSimilarityThreshold())
                .isEqualTo(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL);
    }

    @Test
    void relaxedRetryKeepsQueryTopKAndFilter() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service(vectorStore).retrieveWithRelaxedFallback("泛问", "tenant_id == \"u-x\" && chat_id == \"doc-1\"");

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, times(2)).similaritySearch(captor.capture());
        SearchRequest relaxed = captor.getAllValues().get(1);
        assertThat(relaxed.getQuery()).isEqualTo("泛问");
        assertThat(relaxed.getTopK()).isEqualTo(new RagProperties().getRetrieveTopK());
        assertThat(relaxed.getFilterExpression()).isNotNull();
    }

    @Test
    void relaxedResultsBelowRelevanceFloorAreDiscarded() {
        // 完全无关的问题：放宽重试捞回的"最近邻"相似度全低于无关线 → 整批作废，
        // 不再带着答非所问的引用脚注去调模型
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of()) // 严格阈值下为空
                .thenReturn(List.of(
                        Document.builder().text("Java 面试题内容")
                                .metadata(java.util.Map.of("distance", 0.85)).build(), // 相似度 0.15
                        Document.builder().text("另一段 Java 内容")
                                .metadata(java.util.Map.of("distance", 0.78)).build())); // 相似度 0.22

        List<Document> docs = service(vectorStore).retrieveWithRelaxedFallback("推荐几个长春美食", "tenant_id == \"t\"");

        assertThat(docs).isEmpty();
        verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void relaxedResultsAboveFloorSurviveForGenericQuestions() {
        // 泛问：兜底捞回的最近邻相似度在无关线之上 → 照常保留，泛问通道不被误伤；
        // 分数读不出来的切片保守保留（元数据缺失不使兜底失效）
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        Document.builder().text("这份文档的整体结构说明")
                                .metadata(java.util.Map.of("distance", 0.55)).build(), // 相似度 0.45
                        Document.builder().text("无分数元数据的切片").build()));

        List<Document> docs = service(vectorStore).retrieveWithRelaxedFallback("这份文档讲了什么", "tenant_id == \"t\"");

        assertThat(docs).hasSize(2);
    }
}
