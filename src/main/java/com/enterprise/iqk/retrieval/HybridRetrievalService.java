package com.enterprise.iqk.retrieval;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 混合检索核心服务：向量 / 关键词 / 图谱 / 网络四路并行召回，
 * 各路结果按来源权重加权（finalScore = retrievalScore × 权重），再按内容指纹去重、
 * 按 finalScore 降序取 topK。
 * 设计要点：各源均为阻塞 IO，跑在专用线程池上以免拖垮公共 ForkJoinPool；
 * 单路故障（异常/超时）降级返回空列表并计入按路指标（retrieval.source.requests），
 * 结果携带 degradedSources、整体 outcome 标注 degraded / degraded-empty，
 * 局部故障不会被伪装成"知识库为空"的系统性空结果。
 * 超时是"真取消"：调度超时回调会 cancel 底层任务（排队中的直接跳过、
 * 运行中的收到中断），慢依赖不会持续占用线程拖垮整池。
 */
@Slf4j
@Service
public class HybridRetrievalService {

    private final VectorRetriever vectorRetriever;
    private final KeywordRetriever keywordRetriever;
    private final GraphRetriever graphRetriever;
    private final WebRetriever webRetriever;
    private final MeterRegistry meterRegistry;
    /** 单路检索超时（毫秒），超时取消底层任务并降级为空列表 */
    private final long sourceTimeoutMs;

    // 各检索来源执行的是阻塞 IO；若在 ForkJoinPool.commonPool() 上运行，
    // 可能导致 JVM 中其他并行流饥饿，因此使用专用线程池。
    // 池容量默认 16（4 路 × 4 并发请求），可配 app.retrieval.pool-size。
    private final ThreadPoolExecutor retrievalExecutor;
    /** 超时调度器：到点触发"取消 + 降级"，与检索任务执行隔离 */
    private final ScheduledExecutorService timeoutScheduler;

    public HybridRetrievalService(VectorRetriever vectorRetriever,
                                   KeywordRetriever keywordRetriever,
                                   GraphRetriever graphRetriever,
                                   WebRetriever webRetriever,
                                   MeterRegistry meterRegistry,
                                   @Value("${app.retrieval.source-timeout-ms:3000}") long sourceTimeoutMs,
                                   @Value("${app.retrieval.pool-size:16}") int poolSize) {
        this.vectorRetriever = vectorRetriever;
        this.keywordRetriever = keywordRetriever;
        this.graphRetriever = graphRetriever;
        this.webRetriever = webRetriever;
        this.meterRegistry = meterRegistry;
        this.sourceTimeoutMs = sourceTimeoutMs;
        this.retrievalExecutor = new ThreadPoolExecutor(poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "hybrid-retrieval");
            thread.setDaemon(true);
            return thread;
        });
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hybrid-retrieval-timeout");
            thread.setDaemon(true);
            return thread;
        });
        Gauge.builder("retrieval.pool.active", retrievalExecutor, ThreadPoolExecutor::getActiveCount)
                .description("Active hybrid retrieval worker threads")
                .register(meterRegistry);
        Gauge.builder("retrieval.pool.queued", retrievalExecutor, e -> e.getQueue().size())
                .description("Queued hybrid retrieval tasks")
                .register(meterRegistry);
    }

    /** 应用关闭时立即关闭检索线程池与超时调度器，避免残留阻塞中的检索任务 */
    @PreDestroy
    void shutdownRetrievalExecutor() {
        retrievalExecutor.shutdownNow();
        timeoutScheduler.shutdownNow();
    }

    /** 默认来源权重（向量/关键词/图谱/网络），与 HybridWeights.DEFAULT 保持一致 */
    private static final double VECTOR_WEIGHT = 0.40;
    private static final double KEYWORD_WEIGHT = 0.25;
    private static final double GRAPH_WEIGHT = 0.20;
    private static final double WEB_WEIGHT = 0.15;

    /**
     * 使用默认来源权重的混合检索获取文档。
     * 等价于以默认权重（vector=0.40、keyword=0.25、graph=0.20、web=0.15）调用
     * {@link #retrieve(String, String, String, int, HybridWeights)}。
     *
     * @param query    搜索查询
     * @param tenantId 用于过滤的租户标识
     * @param chatId   用于过滤的会话标识
     * @param topK     返回的头部结果数量
     * @return 混合检索结果，包含去重且已评分的文档
     */
    public HybridRetrievalResult retrieve(String query, String tenantId, String chatId, int topK) {
        return retrieve(query, tenantId, chatId, topK, HybridWeights.DEFAULT);
    }

    /**
     * 使用按来源可配置权重的混合检索获取文档。
     * 各来源通过 {@link CompletableFuture} 并行执行，结果会被合并、
     * 按内容指纹去重，并按最终加权得分排序。
     *
     * 调优提示：事实型/定义型查询适合调高向量权重；
     * 精确匹配/查找型查询适合调高关键词权重。
     *
     * @param query    搜索查询
     * @param tenantId 用于过滤的租户标识
     * @param chatId   用于过滤的会话标识
     * @param topK     返回的头部结果数量
     * @param weights  按来源的权重配置；权重会被归一化为总和 1.0
     * @return 混合检索结果，包含去重且已评分的文档
     */
    public HybridRetrievalResult retrieve(String query, String tenantId, String chatId, int topK, HybridWeights weights) {

        // 将权重归一化为总和 1.0
        HybridWeights normalized = weights.normalize();

        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            CompletableFuture<RouteResult> vectorFuture = retrieveAsync("vector",
                    () -> vectorRetriever.retrieve(query, tenantId, chatId), normalized.vectorWeight());
            CompletableFuture<RouteResult> keywordFuture = retrieveAsync("keyword",
                    () -> keywordRetriever.retrieve(query, tenantId, chatId, topK), normalized.keywordWeight());
            CompletableFuture<RouteResult> graphFuture = retrieveAsync("graph",
                    () -> graphRetriever.retrieve(query, tenantId, topK), normalized.graphWeight());
            CompletableFuture<RouteResult> webFuture = retrieveAsync("web",
                    () -> webRetriever.retrieve(query, topK), normalized.webWeight());

            List<RouteResult> routes = Stream.of(vectorFuture, keywordFuture, graphFuture, webFuture)
                    .map(CompletableFuture::join)
                    .toList();
            List<ScoredDocument> allDocs = routes.stream()
                    .flatMap(route -> route.docs().stream())
                    .toList();
            List<String> degradedSources = routes.stream()
                    .filter(RouteResult::degraded)
                    .map(RouteResult::source)
                    .toList();

            // 按内容指纹去重
            List<ScoredDocument> deduped = deduplicate(allDocs);

            // 按加权得分降序排序
            deduped.sort(Comparator.comparingDouble(ScoredDocument::getFinalScore).reversed());

            List<ScoredDocument> top = deduped.stream().limit(topK).toList();

            outcome = resolveOutcome(top, degradedSources);
            if (!degradedSources.isEmpty()) {
                // 局部故障显式留痕：哪些路降级了，不再伪装成"知识库为空"
                log.warn("hybrid retrieval degraded routes: {}, query='{}'",
                        degradedSources, query);
            }
            return new HybridRetrievalResult(top, allDocs.size(), deduped.size(), degradedSources);
        } finally {
            sample.stop(Timer.builder("retrieval.hybrid.latency")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    /** 整体结局标签：成功/空 + 是否有路降级（degraded-empty 即"用户看到空但其实是故障降级"） */
    private String resolveOutcome(List<ScoredDocument> top, List<String> degradedSources) {
        if (degradedSources.isEmpty()) {
            return top.isEmpty() ? "empty" : "success";
        }
        return top.isEmpty() ? "degraded-empty" : "degraded";
    }

    /** 将各文档原始检索分乘以来源权重写入 finalScore，返回同一列表 */
    private List<ScoredDocument> applyWeight(List<ScoredDocument> docs, double weight) {
        for (ScoredDocument d : docs) {
            d.setFinalScore(d.getRetrievalScore() * weight);
        }
        return docs;
    }

    /**
     * 单路检索：跑在专用线程池上，sourceTimeoutMs 内未完成则取消底层任务并降级为空。
     * 不用 completeOnTimeout —— 它只完成 future 不取消任务：web 路读超时 8 秒、
     * 向量路无语句超时，慢依赖会在调用方放弃后继续占用线程，池被拖满后
     * 后续请求"整池 3 秒超时"，单依赖故障升级成系统性空结果。
     * 真取消 = cancel(true)：排队中的任务直接跳过（立即腾出容量），
     * 运行中的任务收到中断（HTTP 类 IO 可中断释放）。
     */
    private CompletableFuture<RouteResult> retrieveAsync(String source,
                                                         Supplier<List<ScoredDocument>> retrieval,
                                                         double weight) {
        CompletableFuture<RouteResult> promise = new CompletableFuture<>();
        Future<?> worker = retrievalExecutor.submit(() -> {
            try {
                List<ScoredDocument> docs = applyWeight(retrieval.get(), weight);
                if (promise.complete(new RouteResult(source, docs, false))) {
                    countSource(source, "success");
                }
            } catch (RuntimeException ex) {
                if (promise.complete(new RouteResult(source, List.of(), true))) {
                    log.warn("hybrid retrieval source failed: source={}, reason={}", source, ex.toString());
                    countSource(source, "error");
                }
            }
        });
        timeoutScheduler.schedule(() -> {
            // 抢到首次完成 = 正常路径未及完成：取消底层任务，降级为空
            if (promise.complete(new RouteResult(source, List.of(), true))) {
                worker.cancel(true);
                countSource(source, "timeout");
                log.warn("hybrid retrieval source timeout: source={}, timeoutMs={} (task cancelled, degraded to empty)",
                        source, sourceTimeoutMs);
            }
        }, sourceTimeoutMs, TimeUnit.MILLISECONDS);
        return promise;
    }

    /** 按路计数：source × outcome（success / error / timeout），局部故障可观测 */
    private void countSource(String source, String outcome) {
        Counter.builder("retrieval.source.requests")
                .tag("source", source)
                .tag("outcome", outcome)
                .description("Hybrid retrieval per-source outcomes")
                .register(meterRegistry)
                .increment();
    }

    /** 按内容指纹去重，同指纹保留 finalScore 更高者，整体保持首次出现顺序 */
    private List<ScoredDocument> deduplicate(List<ScoredDocument> docs) {
        Map<String, ScoredDocument> seen = new LinkedHashMap<>();
        for (ScoredDocument d : docs) {
            String fingerprint = fingerprint(d);
            ScoredDocument existing = seen.get(fingerprint);
            if (existing == null || d.getFinalScore() > existing.getFinalScore()) {
                seen.put(fingerprint, d);
            }
        }
        return new ArrayList<>(seen.values());
    }

    /** 生成去重指纹：内容折叠空白后取前 200 个字符（null 内容按空串处理） */
    private String fingerprint(ScoredDocument d) {
        String content = d.getContent() != null ? d.getContent() : "";
        // 取前 200 个字符作为去重键
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }

    /** 单路检索结果：文档 + 是否降级（异常或超时按空列表降级） */
    private record RouteResult(String source, List<ScoredDocument> docs, boolean degraded) {
    }

    /** 混合检索结果：topK 文档 + 去重前/后的总条数 + 降级路清单（空 = 四路全部健康） */
    public record HybridRetrievalResult(List<ScoredDocument> documents, int totalBeforeDedup,
                                         int totalAfterDedup, List<String> degradedSources) {}
}
