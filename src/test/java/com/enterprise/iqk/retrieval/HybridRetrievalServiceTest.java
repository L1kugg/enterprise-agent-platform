package com.enterprise.iqk.retrieval;

import com.enterprise.iqk.retrieval.web.WebSearchProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HybridRetrievalServiceTest {

    private VectorRetriever vectorRetriever;
    private KeywordRetriever keywordRetriever;
    private GraphRetriever graphRetriever;
    private WebRetriever webRetriever;

    @BeforeEach
    void setUp() {
        vectorRetriever = mock(VectorRetriever.class);
        keywordRetriever = mock(KeywordRetriever.class);
        graphRetriever = mock(GraphRetriever.class);
        webRetriever = mock(WebRetriever.class);
    }

    /** 统一构造入口：webEnabled 控制网络路是否启用（禁用时不提交任务、不占线程槽）。 */
    private HybridRetrievalService service(SimpleMeterRegistry registry, long timeoutMs,
                                           int poolSize, int queueCapacity, boolean webEnabled) {
        return service(registry, timeoutMs, poolSize, queueCapacity, webEnabled, HybridWeights.DEFAULT);
    }

    /** 带自定义来源权重的构造入口（权重归一化后参与打分，并回带在检索结果上）。 */
    private HybridRetrievalService service(SimpleMeterRegistry registry, long timeoutMs,
                                           int poolSize, int queueCapacity, boolean webEnabled,
                                           HybridWeights weights) {
        WebSearchProperties webSearchProperties = new WebSearchProperties();
        webSearchProperties.setEnabled(webEnabled);
        return new HybridRetrievalService(vectorRetriever, keywordRetriever, graphRetriever, webRetriever,
                webSearchProperties, registry, timeoutMs, poolSize, queueCapacity,
                weights.vectorWeight(), weights.keywordWeight(), weights.graphWeight(), weights.webWeight());
    }

    @Test
    void configuredWeightsNormalizeFlowIntoResultAndScoring() {
        HybridRetrievalService service = service(new SimpleMeterRegistry(), 3000, 8, 64, true,
                new HybridWeights(0.7, 0.1, 0.1, 0.1));

        when(vectorRetriever.retrieve("q", "tenant", "chat"))
                .thenReturn(List.of(doc("vec-1", "vector", "vector lane content", 0.5)));
        when(keywordRetriever.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(doc("kw-1", "keyword", "keyword lane content", 0.5)));
        when(graphRetriever.retrieve(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(doc("graph-1", "graph", "graph lane content", 0.5)));
        when(webRetriever.retrieve(anyString(), anyInt()))
                .thenReturn(List.of(doc("web-1", "web", "web lane content", 0.5)));

        HybridRetrievalService.HybridRetrievalResult result = service.retrieve("q", "tenant", "chat", 4);

        // 配置权重（总和恰为 1，归一化保持原值）回带在结果上，供前端如实绘制
        assertThat(result.weights()).isEqualTo(new HybridWeights(0.7, 0.1, 0.1, 0.1));
        // 同分文档按配置权重排序：向量 0.35 > 其余 0.05
        assertThat(result.documents()).extracting(ScoredDocument::getDocId)
                .containsExactly("vec-1", "kw-1", "graph-1", "web-1");
    }

    @Test
    void degradesWhenOneRetrievalSourceFails() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HybridRetrievalService service = service(registry, 3000, 8, 64, true);

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
        assertThat(awaitCounterCount(registry, "vector", "error")).isEqualTo(1.0);
        assertThat(awaitCounterCount(registry, "keyword", "success")).isEqualTo(1.0);
    }

    @Test
    void deduplicatesByContentFingerprintAndSortsByFinalScore() {
        HybridRetrievalService service = service(new SimpleMeterRegistry(), 3000, 8, 64, true);

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
        HybridRetrievalService service = service(new SimpleMeterRegistry(), 3000, 8, 64, true);

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
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 模拟 pgvector 抖动：向量路阻塞不返回（只能被中断打破），其余路健康
        HybridRetrievalService service = service(registry, 150, 8, 64, true);
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
        assertThat(awaitCounterCount(registry, "vector", "timeout")).isEqualTo(1.0);
        // 真取消：底层任务收到中断并退出（completeOnTimeout 时代任务会继续占用线程空转）
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void allRoutesTimingOutIsVisibleAsDegradedEmptyNotSilentEmpty() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 四路全部阻塞：此时用户"看到空"必须是显式降级，而非系统性空结果
        HybridRetrievalService service = service(registry, 100, 8, 64, true);
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

    @Test
    void topKCutReservesSlotsForLowerWeightSources() {
        HybridRetrievalService service = service(new SimpleMeterRegistry(), 3000, 8, 64, true);

        // 向量路 3 条高分证据（0.9*0.40=0.36），图谱路 1 条（0.85*0.20=0.17）：
        // 全局 topK=2 会把图谱完全挤出；按来源轮转配额图谱必占一席
        when(vectorRetriever.retrieve("q", "tenant", "chat"))
                .thenReturn(List.of(
                        doc("vec-1", "vector", "vector chunk one", 0.9),
                        doc("vec-2", "vector", "vector chunk two", 0.9),
                        doc("vec-3", "vector", "vector chunk three", 0.9)));
        when(keywordRetriever.retrieve(eq("q"), eq("tenant"), eq("chat"), eq(2)))
                .thenReturn(List.of());
        when(graphRetriever.retrieve(eq("q"), eq("tenant"), eq(2)))
                .thenReturn(List.of(doc("graph-1", "graph", "graph entity evidence", 0.85)));
        when(webRetriever.retrieve(eq("q"), eq(2))).thenReturn(List.of());

        HybridRetrievalService.HybridRetrievalResult result = service.retrieve("q", "tenant", "chat", 2);

        assertThat(result.documents()).extracting(ScoredDocument::getDocId)
                .containsExactlyInAnyOrder("vec-1", "graph-1");
        // 最终仍按 finalScore 降序
        assertThat(result.documents().get(0).getFinalScore())
                .isGreaterThanOrEqualTo(result.documents().get(1).getFinalScore());
    }

    @Test
    void queuedTaskDoesNotBurnTimeoutBudget() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 单 worker：第一次检索的向量路占住线程，第二次检索的三路只能排队
        HybridRetrievalService service = service(registry, 300, 1, 64, true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(vectorRetriever.retrieve(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    started.countDown();
                    release.await();
                    return List.of(doc("vec-1", "vector", "vector chunk", 0.9));
                });
        when(keywordRetriever.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(doc("kw-1", "keyword", "keyword chunk", 0.5)));
        when(graphRetriever.retrieve(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(webRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());

        AtomicReference<HybridRetrievalService.HybridRetrievalResult> firstResult = new AtomicReference<>();
        Thread first = new Thread(() -> firstResult.set(service.retrieve("q", "tenant", "chat", 3)));
        first.start();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        AtomicReference<HybridRetrievalService.HybridRetrievalResult> secondResult = new AtomicReference<>();
        Thread second = new Thread(() -> secondResult.set(service.retrieve("q2", "tenant", "chat", 3)));
        second.start();
        // 让排队时长明确超过一个超时周期（300ms）：这是测试前提的确定性保证，非竞态等待。
        // 旧实现超时从提交起算，此处的排队任务会在队列里被"烧光预算"集体假超时。
        Thread.sleep(500);
        release.countDown();
        first.join(5000);
        second.join(5000);

        // 排队任务开跑后正常完成：不降级、不超时
        assertThat(firstResult.get()).isNotNull();
        assertThat(secondResult.get()).isNotNull();
        assertThat(secondResult.get().degradedSources()).isEmpty();
        assertThat(secondResult.get().documents()).extracting(ScoredDocument::getDocId)
                .containsExactlyInAnyOrder("vec-1", "kw-1");
        assertThat(awaitCounterCount(registry, "keyword", "success")).isEqualTo(2.0);
        assertThat(registry.find("retrieval.source.requests")
                .tag("source", "keyword").tag("outcome", "timeout").counter()).isNull();
    }

    @Test
    void saturatedQueueRejectsWithDegradedRouteAndCounter() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 单 worker + 队列容量 1：第一个请求占住 worker、占满队列后，后续提交被拒
        HybridRetrievalService service = service(registry, 3000, 1, 1, false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(vectorRetriever.retrieve(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    started.countDown();
                    release.await();
                    return List.of(doc("vec-1", "vector", "vector chunk", 0.9));
                });
        when(keywordRetriever.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(doc("kw-1", "keyword", "keyword chunk", 0.5)));
        when(graphRetriever.retrieve(anyString(), anyString(), anyInt())).thenReturn(List.of());

        AtomicReference<HybridRetrievalService.HybridRetrievalResult> firstResult = new AtomicReference<>();
        Thread first = new Thread(() -> firstResult.set(service.retrieve("q", "tenant", "chat", 3)));
        first.start();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        // worker 被占且队列已满：三路全部被拒，立即降级（saturated），不无限堆积
        HybridRetrievalService.HybridRetrievalResult secondResult = service.retrieve("q", "tenant", "chat", 3);
        assertThat(secondResult.degradedSources())
                .containsExactlyInAnyOrder("vector", "keyword", "graph");
        assertThat(secondResult.documents()).isEmpty();

        release.countDown();
        first.join(5000);

        // 与提交交错顺序无关的总量断言：被挤掉的路记 saturated，排上队的那路正常成功
        assertThat(firstResult.get()).isNotNull();
        assertThat(firstResult.get().degradedSources()).contains("graph");
        assertThat(firstResult.get().documents()).extracting(ScoredDocument::getDocId)
                .contains("vec-1");
        assertThat(awaitCounterCount(registry, "vector", "saturated")).isEqualTo(1.0);
        assertThat(awaitCounterCount(registry, "keyword", "saturated")).isEqualTo(1.0);
        assertThat(awaitCounterCount(registry, "graph", "saturated")).isEqualTo(2.0);
        assertThat(awaitCounterCount(registry, "keyword", "success")).isEqualTo(1.0);
    }

    @Test
    void webDisabledSkipsSlotAndCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HybridRetrievalService service = service(registry, 3000, 8, 64, false);

        when(vectorRetriever.retrieve("q", "tenant", "chat"))
                .thenReturn(List.of(doc("vec-1", "vector", "vector chunk", 0.9)));
        when(keywordRetriever.retrieve(eq("q"), eq("tenant"), eq("chat"), eq(3)))
                .thenReturn(List.of(doc("kw-1", "keyword", "keyword chunk", 0.5)));
        when(graphRetriever.retrieve("q", "tenant", 3)).thenReturn(List.of());

        HybridRetrievalService.HybridRetrievalResult result = service.retrieve("q", "tenant", "chat", 3);

        // 禁用是配置而非故障：不调用、不降级、不占线程槽、不产生任何按路计数
        verifyNoInteractions(webRetriever);
        assertThat(result.degradedSources()).isEmpty();
        assertThat(result.documents()).extracting(ScoredDocument::getDocId)
                .containsExactlyInAnyOrder("vec-1", "kw-1");
        assertThat(registry.find("retrieval.queue.wait").tag("source", "web").timer()).isNull();
    }

    @Test
    void interruptedWorkerStillCompletesPromiseAsDegraded() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HybridRetrievalService service = service(registry, 3000, 8, 64, false);

        // 停机中断以受检异常出现：catch 必须接住并完成 promise，否则调用线程 join 永挂
        when(vectorRetriever.retrieve(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    throw new InterruptedException("simulated shutdown interrupt");
                });
        when(keywordRetriever.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(doc("kw-1", "keyword", "keyword chunk", 0.5)));
        when(graphRetriever.retrieve(anyString(), anyString(), anyInt())).thenReturn(List.of());

        HybridRetrievalService.HybridRetrievalResult result = service.retrieve("q", "tenant", "chat", 3);

        assertThat(result.degradedSources()).containsExactly("vector");
        assertThat(result.documents()).extracting(ScoredDocument::getDocId)
                .containsExactly("kw-1");
        assertThat(awaitCounterCount(registry, "vector", "error")).isEqualTo(1.0);
    }

    @Test
    void queueWaitTimerRecordedPerSource() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HybridRetrievalService service = service(registry, 3000, 8, 64, true);

        when(vectorRetriever.retrieve("q", "tenant", "chat"))
                .thenReturn(List.of(doc("vec-1", "vector", "vector chunk", 0.9)));
        when(keywordRetriever.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(graphRetriever.retrieve(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(webRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());

        service.retrieve("q", "tenant", "chat", 3);

        // 排队时长按路观测：记录发生在 promise 完成之前，retrieve 返回即已落表
        assertThat(registry.get("retrieval.queue.wait").tag("source", "vector").timer().count())
                .isEqualTo(1L);
        assertThat(registry.get("retrieval.queue.wait").tag("source", "keyword").timer().count())
                .isEqualTo(1L);
        assertThat(registry.get("retrieval.queue.wait").tag("source", "web").timer().count())
                .isEqualTo(1L);
    }

    /**
     * 按路计数在 promise.complete 之后才执行（防超时/异常双计），主线程拿到检索结果时
     * 工作线程可能还没把计数落表；轮询等待落表，避免断言竞态。
     */
    private double awaitCounterCount(SimpleMeterRegistry registry, String source, String outcome) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            var counter = registry.find("retrieval.source.requests")
                    .tag("source", source).tag("outcome", outcome).counter();
            if (counter != null) {
                return counter.count();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("metric not recorded within 2s: source=" + source + ", outcome=" + outcome);
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
