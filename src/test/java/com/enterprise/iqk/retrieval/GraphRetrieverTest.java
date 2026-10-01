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
}
