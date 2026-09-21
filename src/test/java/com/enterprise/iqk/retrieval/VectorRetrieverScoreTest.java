package com.enterprise.iqk.retrieval;

import com.enterprise.iqk.config.properties.RagProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorRetrieverScoreTest {

    @Test
    void usesExplicitScoreFromMetadataWhenAvailable() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder()
                        .text("alpha")
                        .metadata("file_name", "a.txt")
                        .metadata("chunk_index", 0)
                        .metadata("score", 0.93)
                        .build()));
        VectorRetriever retriever = new VectorRetriever(vectorStore, new RagProperties(), new SimpleMeterRegistry());

        List<ScoredDocument> docs = retriever.retrieve("q", "tenant", "chat");

        assertThat(docs).singleElement().satisfies(d -> {
            assertThat(d.getRetrievalScore()).isEqualTo(0.93);
            assertThat(d.getSourceType()).isEqualTo("vector");
        });
    }

    @Test
    void convertsDistanceMetadataIntoScore() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder()
                        .text("beta")
                        .metadata("distance", 0.2)
                        .build()));
        VectorRetriever retriever = new VectorRetriever(vectorStore, new RagProperties(), new SimpleMeterRegistry());

        List<ScoredDocument> docs = retriever.retrieve("q", "tenant", "chat");

        assertThat(docs.get(0).getRetrievalScore()).isEqualTo(0.8);
    }

    @Test
    void fallsBackToRankBasedScoreWhenMetadataMissing() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().text("a").metadata(Map.of()).build(),
                Document.builder().text("b").metadata(Map.of()).build()));
        VectorRetriever retriever = new VectorRetriever(vectorStore, new RagProperties(), new SimpleMeterRegistry());

        List<ScoredDocument> docs = retriever.retrieve("q", "tenant", "chat");

        // 第一条文档仍在分数下限之上，第二条按 0.05 递减。
        assertThat(docs.get(0).getRetrievalScore()).isBetween(0.99, 1.0);
        assertThat(docs.get(1).getRetrievalScore()).isBetween(0.9, 0.96);
    }

    @Test
    void filterIsTenantScopedWithoutChatId() {
        VectorRetriever retriever = new VectorRetriever(
                mock(VectorStore.class), new RagProperties(), new SimpleMeterRegistry());

        // 知识库按租户共享：过滤表达式不得再含 chat_id
        //（否则 A 会话上传的文档 B 会话检索不到，临时 chatId 的调用方必空）
        assertThat(retriever.filterExpression("tenant-1")).isEqualTo("tenant_id == \"tenant-1\"");
    }

    @Test
    void sameChatDocumentGetsBoundedBoost() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().text("same")
                        .metadata(Map.of("score", 0.8, "chat_id", "chat-1")).build(),
                Document.builder().text("other")
                        .metadata(Map.of("score", 0.8, "chat_id", "chat-9")).build()));
        VectorRetriever retriever = new VectorRetriever(vectorStore, new RagProperties(), new SimpleMeterRegistry());

        List<ScoredDocument> docs = retriever.retrieve("q", "tenant", "chat-1");

        // 词面/语义得分相同的两条，同会话一条 +0.05——会话相关性是加分项而不是硬边界
        assertThat(docs.get(0).getRetrievalScore()).isCloseTo(0.85, within(1e-9));
        assertThat(docs.get(1).getRetrievalScore()).isCloseTo(0.80, within(1e-9));
    }
}
