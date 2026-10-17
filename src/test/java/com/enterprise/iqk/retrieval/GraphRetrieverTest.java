package com.enterprise.iqk.retrieval;

import com.enterprise.iqk.graph.GraphService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GraphRetrieverTest {

    private final GraphRetriever retriever =
            new GraphRetriever(mock(GraphService.class), new SimpleMeterRegistry());

    @Test
    void mixedQueryExtractsLatinTokensAndCjkWindows() {
        // 汉字在正则里也算字母：整句是一个 token，必须能切出类名和中文短词
        List<String> keywords = retriever.extractKeywords("ArrayList 的内部实现和扩容机制相关的要点是什么？");

        assertThat(keywords).contains("ArrayList");
        assertThat(keywords).contains("扩容");
        assertThat(keywords).hasSizeLessThanOrEqualTo(12);
        // 拉丁词优先占位（实体名多为类名）
        assertThat(keywords.get(0)).isEqualTo("ArrayList");
    }

    @Test
    void pureChineseQueryProducesShortWindowCandidates() {
        List<String> keywords = retriever.extractKeywords("高温天气对身体的健康风险有哪些？");

        assertThat(keywords).isNotEmpty();
        assertThat(keywords).contains("高温");
        assertThat(keywords).allSatisfy(kw -> assertThat(kw.length()).isBetween(2, 4));
    }

    @Test
    void shortCjkRunKeptAsWholeCandidate() {
        List<String> keywords = retriever.extractKeywords("什么是微服务");

        assertThat(keywords).contains("微服务");
    }

    @Test
    void blankQueryReturnsNoCandidates() {
        assertThat(retriever.extractKeywords("")).isEmpty();
        assertThat(retriever.extractKeywords("   ")).isEmpty();
    }

    @Test
    void scopedRetrievePassesBoundKnowledgeBasesToGraphQueries() {
        com.enterprise.iqk.graph.KgEntityRecord entity = com.enterprise.iqk.graph.KgEntityRecord.builder()
                .entityId("e-1").name("订单服务").type("SERVICE").build();
        com.enterprise.iqk.graph.KgFactRecord fact = com.enterprise.iqk.graph.KgFactRecord.builder()
                .factId("f-1").subject("订单服务").predicate("依赖").object("库存服务").confidence(0.9).build();
        GraphService graphService = mock(GraphService.class);
        org.mockito.Mockito.when(graphService.searchEntitiesByKeywords(
                org.mockito.ArgumentMatchers.eq("tenant"), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(List.of(1L, 2L))))
                .thenReturn(List.of(entity));
        org.mockito.Mockito.when(graphService.searchFactsByKeywords(
                org.mockito.ArgumentMatchers.eq("tenant"), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(List.of(1L, 2L))))
                .thenReturn(List.of(fact));
        GraphRetriever scopedRetriever = new GraphRetriever(graphService, new SimpleMeterRegistry());

        List<ScoredDocument> docs = scopedRetriever.retrieve("订单服务", "tenant", 5, KnowledgeScope.of(List.of(1L, 2L)));

        // 实体 + 事实都只取自绑定知识库圈定的图谱子集
        assertThat(docs).hasSize(2);
        org.mockito.Mockito.verify(graphService).searchEntitiesByKeywords(
                org.mockito.ArgumentMatchers.eq("tenant"), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(List.of(1L, 2L)));
        org.mockito.Mockito.verify(graphService).searchFactsByKeywords(
                org.mockito.ArgumentMatchers.eq("tenant"), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(List.of(1L, 2L)));
    }

    @Test
    void unrestrictedRetrieveUsesUnscopedGraphQueries() {
        GraphService graphService = mock(GraphService.class);
        org.mockito.Mockito.when(graphService.searchEntitiesByKeywords(
                org.mockito.ArgumentMatchers.eq("tenant"), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of());
        org.mockito.Mockito.when(graphService.searchFactsByKeywords(
                org.mockito.ArgumentMatchers.eq("tenant"), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of());
        GraphRetriever unscopedRetriever = new GraphRetriever(graphService, new SimpleMeterRegistry());

        unscopedRetriever.retrieve("订单服务", "tenant", 5);

        // 不限库走原路径：kbIds 传 null，服务层保持租户级查询
        org.mockito.Mockito.verify(graphService).searchEntitiesByKeywords(
                org.mockito.ArgumentMatchers.eq("tenant"), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.isNull());
    }
}
