package com.enterprise.iqk.retrieval;

import com.enterprise.iqk.config.properties.RagProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 向量路知识库作用域（FR-R5-01）：圈定时按切片元数据 knowledge_base_id 后置过滤，
 * 候选池放大 3 倍补偿过滤损耗；不限库时透传全部结果、候选量保持配置值。
 */
class VectorRetrieverKnowledgeScopeTest {

    @Test
    void scopedRetrieveFiltersDocumentsByKnowledgeBaseMetadata() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().text("kb1").metadata(Map.of("knowledge_base_id", 1L)).build(),
                Document.builder().text("kb2").metadata(Map.of("knowledge_base_id", 2L)).build(),
                Document.builder().text("legacy").metadata(Map.of()).build()));
        VectorRetriever retriever = new VectorRetriever(vectorStore, new RagProperties(), new SimpleMeterRegistry());

        List<ScoredDocument> docs = retriever.retrieve("q", "tenant", "chat", KnowledgeScope.of(List.of(1L)));

        // 只保留绑定库 1 的切片；其他库与未归属文档都被排除
        assertThat(docs).singleElement().satisfies(d -> assertThat(d.getContent()).isEqualTo("kb1"));
    }

    @Test
    void scopedRetrieveBumpsCandidateTopKToCompensateFiltering() {
        VectorStore vectorStore = mock(VectorStore.class);
        List<SearchRequest> captured = new ArrayList<>();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return List.of();
        });
        VectorRetriever retriever = new VectorRetriever(vectorStore, new RagProperties(), new SimpleMeterRegistry());
        int configuredTopK = new RagProperties().getRetrieveTopK();

        retriever.retrieve("q", "tenant", "chat", KnowledgeScope.of(List.of(7L)));
        retriever.retrieve("q", "tenant", "chat", KnowledgeScope.unrestricted());

        assertThat(captured).hasSize(2);
        // 圈定：候选池 ×3（封顶 60）；不限库：保持配置值
        assertThat(captured.get(0).getTopK()).isEqualTo(Math.min(configuredTopK * 3, 60));
        assertThat(captured.get(1).getTopK()).isEqualTo(configuredTopK);
    }

    @Test
    void nullScopeIsTreatedAsUnrestricted() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().text("any").metadata(Map.of()).build()));
        VectorRetriever retriever = new VectorRetriever(vectorStore, new RagProperties(), new SimpleMeterRegistry());

        List<ScoredDocument> docs = retriever.retrieve("q", "tenant", "chat", null);

        assertThat(docs).hasSize(1);
    }
}
