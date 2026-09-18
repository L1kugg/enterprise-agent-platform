package com.enterprise.iqk.retrieval;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {

    private final VectorRetriever vectorRetriever;
    private final KeywordRetriever keywordRetriever;
    private final GraphRetriever graphRetriever;
    private final WebRetriever webRetriever;
    private final MeterRegistry meterRegistry;

    // 各检索来源执行的是阻塞 IO；若在 ForkJoinPool.commonPool() 上运行，
    // 可能导致 JVM 中其他并行流饥饿，因此使用专用线程池。
    private final ExecutorService retrievalExecutor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "hybrid-retrieval");
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    void shutdownRetrievalExecutor() {
        retrievalExecutor.shutdownNow();
    }

    private static final double VECTOR_WEIGHT = 0.40;
    private static final double KEYWORD_WEIGHT = 0.25;
    private static final double GRAPH_WEIGHT = 0.20;
    private static final double WEB_WEIGHT = 0.15;
    private static final long SOURCE_TIMEOUT_SECONDS = 3;

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
            CompletableFuture<List<ScoredDocument>> vectorFuture = retrieveAsync("vector",
                    () -> vectorRetriever.retrieve(query, tenantId, chatId), normalized.vectorWeight());
            CompletableFuture<List<ScoredDocument>> keywordFuture = retrieveAsync("keyword",
                    () -> keywordRetriever.retrieve(query, tenantId, chatId, topK), normalized.keywordWeight());
            CompletableFuture<List<ScoredDocument>> graphFuture = retrieveAsync("graph",
                    () -> graphRetriever.retrieve(query, tenantId, topK), normalized.graphWeight());
            CompletableFuture<List<ScoredDocument>> webFuture = retrieveAsync("web",
                    () -> webRetriever.retrieve(query, topK), normalized.webWeight());

            List<ScoredDocument> allDocs = Stream.of(vectorFuture, keywordFuture, graphFuture, webFuture)
                    .flatMap(future -> future.join().stream())
                    .toList();

            // 按内容指纹去重
            List<ScoredDocument> deduped = deduplicate(allDocs);

            // 按加权得分降序排序
            deduped.sort(Comparator.comparingDouble(ScoredDocument::getFinalScore).reversed());

            List<ScoredDocument> top = deduped.stream().limit(topK).toList();

            outcome = top.isEmpty() ? "empty" : "success";
            return new HybridRetrievalResult(top, allDocs.size(), deduped.size());
        } finally {
            sample.stop(Timer.builder("retrieval.hybrid.latency")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    private List<ScoredDocument> applyWeight(List<ScoredDocument> docs, double weight) {
        for (ScoredDocument d : docs) {
            d.setFinalScore(d.getRetrievalScore() * weight);
        }
        return docs;
    }

    private CompletableFuture<List<ScoredDocument>> retrieveAsync(String source,
                                                                  Supplier<List<ScoredDocument>> retrieval,
                                                                  double weight) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return applyWeight(retrieval.get(), weight);
            } catch (RuntimeException ex) {
                log.warn("hybrid retrieval source failed: source={}, reason={}", source, ex.toString());
                return List.<ScoredDocument>of();
            }
        }, retrievalExecutor).completeOnTimeout(List.of(), SOURCE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

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

    private String fingerprint(ScoredDocument d) {
        String content = d.getContent() != null ? d.getContent() : "";
        // 取前 200 个字符作为去重键
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }

    public record HybridRetrievalResult(List<ScoredDocument> documents, int totalBeforeDedup,
                                         int totalAfterDedup) {}
}
