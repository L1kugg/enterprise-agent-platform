package com.enterprise.iqk.retrieval;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridRetrievalServiceTest {

    @Test
    void degradesWhenOneRetrievalSourceFails() {
        VectorRetriever vectorRetriever = mock(VectorRetriever.class);
        KeywordRetriever keywordRetriever = mock(KeywordRetriever.class);
        GraphRetriever graphRetriever = mock(GraphRetriever.class);
        WebRetriever webRetriever = mock(WebRetriever.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HybridRetrievalService service = new HybridRetrievalService(
                vectorRetriever,
                keywordRetriever,
                graphRetriever,
                webRetriever,
                registry,
                3000,
                8
        );

        when(vectorRetriever.retrieve("spring ai", "tenant", "chat")).thenThrow(new IllegalStateException("down"));
        when(keywordRetriever.retrieve("spring ai", "tenant", "chat", 3))
                .thenReturn(List.of(doc("kw-1", "keyword", "same content", 0.4)));
        when(graphRetriever.retrieve("spring ai", "tenant", 3))
                .thenReturn(List.of(doc("graph-1", "graph", "same content", 0.9)));
        when(webRetriever.retrieve("spring ai", 3)).thenReturn(List.of(doc("web-1", "web", "web content", 0.7)));

        HybridRetrievalService.HybridRetrievalResult result = service.retrieve("spring ai", "tenant", "chat", 3);

        assertThat(result.documents()).extracting(ScoredDocument::getDocId)
                .containsExactly("graph-1", "web-1");
        assertThat(result.totalBeforeDedup()).isEqualTo(3);
        assertThat(result.totalAfterDedup()).isEqualTo(2);
        // 按路计数：故障路记 error、健康路记 success，局部故障可观测
        assertThat(result.degradedSources()).containsExactly("vector");
        assertThat(registry.get("retrieval.source.requests")
                .tag("source", "vector").tag("outcome", "error").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("retrieval.source.requests")
                .tag("source", "keyword").tag("outcome", "success").counter().count()).isEqualTo(1.0);
    }

    @Test
    void deduplicatesByContentFingerprintAndSortsByFinalScore() {
        VectorRetriever vectorRetriever = mock(VectorRetriever.class);
        KeywordRetriever keywordRetriever = mock(KeywordRetriever.class);
        GraphRetriever graphRetriever = mock(GraphRetriever.class);
        WebRetriever webRetriever = mock(WebRetriever.class);
        HybridRetrievalService service = new HybridRetrievalService(
                vectorRetriever,
                keywordRetriever,
                graphRetriever,
                webRetriever,
                new SimpleMeterRegistry(),
                3000,
                8
        );

        when(vectorRetriever.retrieve("q", "tenant", "chat"))
                .thenReturn(List.of(doc("vec-1", "vector", "spring ai tutorial basics", 0.9)));
        when(keywordRetriever.retrieve(eq("q"), eq("tenant"), eq("chat"), eq(5)))
                .thenReturn(List.of(doc("kw-1", "keyword", "spring ai tutorial basics", 0.5),
                        doc("kw-2", "keyword", "another unique chunk", 0.4)));
        when(graphRetriever.retrieve(eq("q"), eq("tenant"), eq(5)))
                .thenReturn(List.of());
        when(webRetriever.retrieve(eq("q"), eq(5))).thenReturn(List.of());

        HybridRetrievalService.HybridRetrievalResult result = service.retrieve("q", "tenant", "chat", 5);

        // 原始文档 3 条，去重后按内容指纹只剩 2 条
        assertThat(result.totalBeforeDedup()).isEqualTo(3);
        assertThat(result.totalAfterDedup()).isEqualTo(2);
        // 相同内容时，vector 0.9 * 0.40 = 0.36
        // 胜过 keyword 0.5 * 0.25 = 0.125
        assertThat(result.documents()).extracting(ScoredDocument::getDocId)
                .containsExactly("vec-1", "kw-2");
        assertThat(result.documents().get(0).getFinalScore()).isGreaterThan(
                result.documents().get(1).getFinalScore());
    }

    @Test
    void allSourcesFailingReturnsEmptyResult() {
        VectorRetriever vectorRetriever = mock(VectorRetriever.class);
        KeywordRetriever keywordRetriever = mock(KeywordRetriever.class);
        GraphRetriever graphRetriever = mock(GraphRetriever.class);
        WebRetriever webRetriever = mock(WebRetriever.class);
        HybridRetrievalService service = new HybridRetrievalService(
                vectorRetriever,
                keywordRetriever,
                graphRetriever,
                webRetriever,
                new SimpleMeterRegistry(),
                3000,
                8
        );

        when(vectorRetriever.retrieve("q", "tenant", "chat")).thenThrow(new RuntimeException("a"));
        when(keywordRetriever.retrieve(any(), any(), any(), anyInt())).thenThrow(new RuntimeException("b"));
        when(graphRetriever.retrieve(any(), any(), anyInt())).thenThrow(new RuntimeException("c"));
        when(webRetriever.retrieve(any(), anyInt())).thenThrow(new RuntimeException("d"));

        HybridRetrievalService.HybridRetrievalResult result = service.retrieve("q", "tenant", "chat", 3);

        assertThat(result.documents()).isEmpty();
        assertThat(result.totalBeforeDedup()).isZero();
        assertThat(result.totalAfterDedup()).isZero();
        assertThat(result.degradedSources())
                .containsExactlyInAnyOrder("vector", "keyword", "graph", "web");
    }

    @Test
    void timeoutCancelsSlowSourceAndMarksItDegraded() throws Exception {
        VectorRetriever vectorRetriever = mock(VectorRetriever.class);
        KeywordRetriever keywordRetriever = mock(KeywordRetriever.class);
        GraphRetriever graphRetriever = mock(GraphRetriever.class);
        WebRetriever webRetriever = mock(WebRetriever.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 模拟 pgvector 抖动：向量路阻塞不返回（只能被中断打破），其余路健康
        HybridRetrievalService service = new HybridRetrievalService(
                vectorRetriever, keywordRetriever, graphRetriever, webRetriever,
                registry, 150, 8);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(vectorRetriever.retrieve("q", "tenant", "chat"))
                .thenAnswer(inv -> blockingRetrieval(interrupted));
        when(keywordRetriever.retrieve(eq("q"), eq("tenant"), eq("chat"), eq(3)))
                .thenReturn(List.of(doc("kw-1", "keyword", "课程内容", 0.5)));
        when(graphRetriever.retrieve("q", "tenant", 3)).thenReturn(List.of());
        when(webRetriever.retrieve("q", 3)).thenReturn(List.of());

        HybridRetrievalService.HybridRetrievalResult result = service.retrieve("q", "tenant", "chat", 3);

        assertThat(result.degradedSources()).containsExactly("vector");
        assertThat(result.documents()).extracting(ScoredDocument::getDocId)
                .containsExactly("kw-1");
        assertThat(registry.get("retrieval.source.requests")
                .tag("source", "vector").tag("outcome", "timeout").counter().count()).isEqualTo(1.0);
        // 真取消：底层任务收到中断并退出（completeOnTimeout 时代任务会继续占用线程空转）
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void allRoutesTimingOutIsVisibleAsDegradedEmptyNotSilentEmpty() {
        VectorRetriever vectorRetriever = mock(VectorRetriever.class);
        KeywordRetriever keywordRetriever = mock(KeywordRetriever.class);
        GraphRetriever graphRetriever = mock(GraphRetriever.class);
        WebRetriever webRetriever = mock(WebRetriever.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 四路全部阻塞：此时用户"看到空"必须是显式降级，而非系统性空结果
        HybridRetrievalService service = new HybridRetrievalService(
                vectorRetriever, keywordRetriever, graphRetriever, webRetriever,
                registry, 100, 8);
        CountDownLatch latch = new CountDownLatch(1);
        when(vectorRetriever.retrieve(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> blockingRetrieval(latch));
        when(keywordRetriever.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> blockingRetrieval(latch));
        when(graphRetriever.retrieve(anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> blockingRetrieval(latch));
        when(webRetriever.retrieve(anyString(), anyInt()))
                .thenAnswer(inv -> blockingRetrieval(latch));

        HybridRetrievalService.HybridRetrievalResult result = service.retrieve("q", "tenant", "chat", 3);

        assertThat(result.documents()).isEmpty();
        assertThat(result.degradedSources())
                .containsExactlyInAnyOrder("vector", "keyword", "graph", "web");
        assertThatCode(() -> registry.get("retrieval.hybrid.latency")
                .tag("outcome", "degraded-empty").timer()).doesNotThrowAnyException();
    }

    /** 模拟不可中断完成的慢检索：永久阻塞，仅线程中断能打破。 */
    private List<ScoredDocument> blockingRetrieval(CountDownLatch interrupted) {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException ex) {
            interrupted.countDown();
            Thread.currentThread().interrupt();
        }
        return List.of();
    }

    private ScoredDocument doc(String id, String sourceType, String content, double score) {
        return ScoredDocument.builder()
                .docId(id)
                .sourceType(sourceType)
                .title(id)
                .content(content)
                .retrievalScore(score)
                .metadata(Map.of())
                .build();
    }
}
