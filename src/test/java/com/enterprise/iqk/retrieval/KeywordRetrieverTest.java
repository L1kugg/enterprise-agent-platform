package com.enterprise.iqk.retrieval;

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

/**
 * 关键词检索路的三个修复点回归：
 * 中文 bigram 命中（此前整句单 token 必空）、长文档不被稀释（分母=查询 token 数）、
 * 租户级软作用域（同会话有界加分，不再硬过滤 chat_id）。
 */
class KeywordRetrieverTest {

    @Test
    void chineseQueryMatchesContentViaBigrams() {
        VectorStore vectorStore = mock(VectorStore.class);
        // 此前"预约"无法命中"课程预约怎么办理"（中文整句成单 token），中文查询必然空结果
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder()
                        .text("课程预约怎么办理")
                        .metadata(Map.of("file_name", "course.pdf", "chat_id", "chat-9"))
                        .build()));
        KeywordRetriever retriever = new KeywordRetriever(vectorStore,
                mock(KeywordIndexStore.class), new SimpleMeterRegistry());

        List<ScoredDocument> docs = retriever.retrieve("预约", "tenant", "chat-1", 5);

        // 查询 token {预约} 全部命中内容 bigram → 内容召回 1.0 × 0.4 = 0.4（跨会话无加成）
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).getRetrievalScore()).isCloseTo(0.4, within(1e-9));
        assertThat(docs.get(0).getSourceType()).isEqualTo("keyword");
    }

    @Test
    void longDocumentIsNotZeroedOutByTargetSize() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder()
                        .text("redis 是一种内存缓存中间件，缓存穿透是需要重点防御的问题，"
                                + "此外还有缓存击穿与缓存雪崩，共同构成缓存系统的三大经典问题。")
                        .metadata(Map.of("file_name", "cache.pdf"))
                        .build()));
        KeywordRetriever retriever = new KeywordRetriever(vectorStore,
                mock(KeywordIndexStore.class), new SimpleMeterRegistry());

        List<ScoredDocument> docs = retriever.retrieve("redis 缓存 穿透", "tenant", "chat-1", 5);

        // 查询 3 词全部命中（redis/缓存/穿透）→ 召回 1.0；此前除以文档 token 数，长文必然趋零
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).getRetrievalScore()).isCloseTo(0.4, within(1e-9));
    }

    @Test
    void sameChatDocumentGetsBoundedBoost() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().text("redis 部署手册")
                        .metadata(Map.of("file_name", "a.pdf", "chat_id", "chat-1")).build(),
                Document.builder().text("redis 部署手册")
                        .metadata(Map.of("file_name", "b.pdf", "chat_id", "chat-9")).build()));
        KeywordRetriever retriever = new KeywordRetriever(vectorStore,
                mock(KeywordIndexStore.class), new SimpleMeterRegistry());

        List<ScoredDocument> docs = retriever.retrieve("redis 部署", "tenant", "chat-1", 5);

        // 两条词面得分相同，同会话一条 +0.05 排前——会话相关性是加分项而不是硬边界
        assertThat(docs).hasSize(2);
        assertThat(docs.get(0).getRetrievalScore())
                .isCloseTo(docs.get(1).getRetrievalScore() + ChatScope.BOOST, within(1e-9));
    }

    @Test
    void filterIsTenantScopedWithoutChatId() {
        KeywordRetriever retriever = new KeywordRetriever(mock(VectorStore.class),
                mock(KeywordIndexStore.class), new SimpleMeterRegistry());

        // 知识库按租户共享：过滤表达式不得再含 chat_id（否则跨会话/临时会话检索必空）
        assertThat(retriever.filterExpression("tenant-1")).isEqualTo("tenant_id == \"tenant-1\"");
    }

    @Test
    void emptyQueryReturnsNoResults() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        KeywordRetriever retriever = new KeywordRetriever(vectorStore,
                mock(KeywordIndexStore.class), new SimpleMeterRegistry());

        assertThat(retriever.retrieve("   ", "tenant", "chat-1", 5)).isEmpty();
    }
}
