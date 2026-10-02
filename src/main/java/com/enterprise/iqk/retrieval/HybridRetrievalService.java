package com.enterprise.iqk.retrieval;

import com.enterprise.iqk.retrieval.web.WebSearchProperties;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 混合检索核心服务：向量 / 关键词 / 图谱 / 网络四路并行召回，
 * 各路结果按来源权重加权（finalScore = retrievalScore × 权重），再按内容指纹去重、
 * 取 topK。topK 截断按来源轮转配额：每轮各健康路出一条本路最高分，避免低权重路
 * （如图谱 0.20）的证据被高权重路整体挤出、永远不可见。
 * 设计要点：各源均为阻塞 IO，跑在专用线程池上以免拖垮公共 ForkJoinPool；
 * 单路故障（异常/超时/被拒）降级返回空列表并计入按路指标（retrieval.source.requests），
 * 结果携带 degradedSources、整体 outcome 标注 degraded / degraded-empty，
 * 局部故障不会被伪装成"知识库为空"的系统性空结果。
 * 超时是"真取消"，且从任务真正开始执行起算——排队等待不占用超时预算，
 * 高并发下的排队不会演变成四路集体"超时降级"的假故障；到点中断运行线程，
 * 慢依赖不会持续占用线程拖垮整池。
 * 队列有界（app.retrieval.queue-capacity）：池与队列打满时提交被拒，立即降级并记
 * saturated 结局，拒绝可见可观测而非在调用线程无限堆积。
 * web 路禁用时不提交任务：不占线程槽、不产生按路计数（禁用是配置而非故障）。
 */
@Slf4j
@Service
public class HybridRetrievalService {

    private final VectorRetriever vectorRetriever;
    private final KeywordRetriever keywordRetriever;
    private final GraphRetriever graphRetriever;
    private final WebRetriever webRetriever;
    private final WebSearchProperties webSearchProperties;
    private final MeterRegistry meterRegistry;
    /** 单路检索超时（毫秒），从任务开始执行起算，超时中断底层执行并降级为空列表 */
    private final long sourceTimeoutMs;
    /** 单路任务队列容量，打满即拒绝（saturated），防无界堆积 */
    private final int queueCapacity;

    // 各检索来源执行的是阻塞 IO；若在 ForkJoinPool.commonPool() 上运行，
    // 可能导致 JVM 中其他并行流饥饿，因此使用专用线程池。
    // 池容量默认 16（4 路 × 4 并发请求），可配 app.retrieval.pool-size；
    // 须 ≤ 数据库连接池（DB_POOL_MAX_SIZE，默认 20）——vector/keyword/graph 三路均直连数据库，
    // 盲目扩线程只会让线程卡在排队拿连接。
    private final ThreadPoolExecutor retrievalExecutor;
    /** 超时调度器：到点触发"取消 + 降级"，与检索任务执行隔离 */
    private final ScheduledExecutorService timeoutScheduler;

    public HybridRetrievalService(VectorRetriever vectorRetriever,
                                   KeywordRetriever keywordRetriever,
                                   GraphRetriever graphRetriever,
                                   WebRetriever webRetriever,
                                   WebSearchProperties webSearchProperties,
                                   MeterRegistry meterRegistry,
                                   @Value("${app.retrieval.source-timeout-ms:3000}") long sourceTimeoutMs,
                                   @Value("${app.retrieval.pool-size:16}") int poolSize,
                                   @Value("${app.retrieval.queue-capacity:64}") int queueCapacity) {
        this.vectorRetriever = vectorRetriever;
        this.keywordRetriever = keywordRetriever;
        this.graphRetriever = graphRetriever;
        this.webRetriever = webRetriever;
        this.webSearchProperties = webSearchProperties;
        this.meterRegistry = meterRegistry;
        this.sourceTimeoutMs = sourceTimeoutMs;
        this.queueCapacity = Math.max(1, queueCapacity);
        this.retrievalExecutor = new ThreadPoolExecutor(Math.max(1, poolSize), Math.max(1, poolSize),
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(this.queueCapacity), runnable -> {
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

    /**
     * 应用关闭时立即关闭检索线程池与超时调度器，避免残留阻塞中的检索任务。
     * 被中断的在途任务由 worker 的 catch(Exception) 兜底完成 promise，调用方 join 不会永挂。
     */
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
            // web 路禁用时不提交任务：不占线程槽、不产生按路计数（禁用是配置而非故障）
            CompletableFuture<RouteResult> webFuture = webSearchProperties.isEnabled()
                    ? retrieveAsync("web", () -> webRetriever.retrieve(query, topK), normalized.webWeight())
                    : CompletableFuture.completedFuture(new RouteResult("web", List.of(), false));

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

            List<ScoredDocument> top = selectTopAcrossSources(deduped, topK);

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
     * topK 截断按来源轮转配额：把去重后的候选（已按 finalScore 降序）按来源分桶，
     * 每轮各来源出一条本路最高分，直到取满 topK 或无候选；最终仍按 finalScore 降序返回。
     * 若不做配额，低权重路（图谱 0.20）即使有高分证据也会被高权重路（向量 0.40）
     * 整体挤出 topK，四路融合退化为"只有向量"。
     */
    private List<ScoredDocument> selectTopAcrossSources(List<ScoredDocument> sorted, int topK) {
        if (sorted.size() <= topK) {
            return sorted;
        }
        Map<String, List<ScoredDocument>> bySource = new LinkedHashMap<>();
        for (ScoredDocument d : sorted) {
            bySource.computeIfAbsent(d.getSourceType() != null ? d.getSourceType() : "unknown",
                    key -> new ArrayList<>()).add(d);
        }
        List<ScoredDocument> selected = new ArrayList<>();
        boolean progressed = true;
        while (selected.size() < topK && progressed) {
            progressed = false;
            for (List<ScoredDocument> lane : bySource.values()) {
                if (lane.isEmpty()) {
                    continue;
                }
                selected.add(lane.remove(0));
                progressed = true;
                if (selected.size() == topK) {
                    break;
                }
            }
        }
        selected.sort(Comparator.comparingDouble(ScoredDocument::getFinalScore).reversed());
        return selected;
    }

    /**
     * 单路检索：跑在专用线程池上，超时从任务真正开始执行起算（排队等待不占预算），
     * sourceTimeoutMs 内未完成则中断执行线程并降级为空。
     * 不用 completeOnTimeout —— 它只完成 future 不取消任务：web 路读超时 8 秒、
     * 向量路无语句超时，慢依赖会在调用方放弃后继续占用线程，池被拖满后
     * 后续请求"整池 3 秒超时"，单依赖故障升级成系统性空结果。
     * 真取消 = 中断运行中线程（HTTP 类 IO 可中断释放）；排队中的任务不调度超时，
     * 排队多久都不会被误判为慢依赖。
     * 池/队列打满（或停机中）时提交被拒：立即降级并记 saturated，不无限堆积。
     */
    private CompletableFuture<RouteResult> retrieveAsync(String source,
                                                         Supplier<List<ScoredDocument>> retrieval,
                                                         double weight) {
        CompletableFuture<RouteResult> promise = new CompletableFuture<>();
        long submittedAtNs = System.nanoTime();
        try {
            retrievalExecutor.execute(() -> {
                Thread workerThread = Thread.currentThread();
                // 排队时长观测：超时预算从这一刻才起算
                Timer.builder("retrieval.queue.wait")
                        .tag("source", source)
                        .description("Hybrid retrieval per-source queue wait before execution")
                        .register(meterRegistry)
                        .record(System.nanoTime() - submittedAtNs, TimeUnit.NANOSECONDS);
                ScheduledFuture<?> timeoutHandle = timeoutScheduler.schedule(() -> {
                    // 抢到首次完成 = 正常路径未及完成：中断执行线程，降级为空
                    if (promise.complete(new RouteResult(source, List.of(), true))) {
                        workerThread.interrupt();
                        countSource(source, "timeout");
                        log.warn("hybrid retrieval source timeout: source={}, timeoutMs={} (task interrupted, degraded to empty)",
                                source, sourceTimeoutMs);
                    }
                }, sourceTimeoutMs, TimeUnit.MILLISECONDS);
                try {
                    List<ScoredDocument> docs = applyWeight(retrieval.get(), weight);
                    if (promise.complete(new RouteResult(source, docs, false))) {
                        countSource(source, "success");
                    }
                } catch (Exception ex) {
                    // 接住 Exception 而非 RuntimeException：停机中断（InterruptedException 是受检异常）
                    // 也必须完成 promise，否则调用线程 join 永挂、优雅停机卡死
                    if (promise.complete(new RouteResult(source, List.of(), true))) {
                        log.warn("hybrid retrieval source failed: source={}, reason={}", source, ex.toString());
                        countSource(source, "error");
                    }
                } finally {
                    timeoutHandle.cancel(false);
                }
            });
        } catch (RejectedExecutionException ex) {
            if (promise.complete(new RouteResult(source, List.of(), true))) {
                log.warn("hybrid retrieval saturated, task rejected: source={}, queueCapacity={}",
                        source, queueCapacity);
                countSource(source, "saturated");
            }
        }
        return promise;
    }

    /** 按路计数：source × outcome（success / error / timeout / saturated），局部故障可观测 */
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
