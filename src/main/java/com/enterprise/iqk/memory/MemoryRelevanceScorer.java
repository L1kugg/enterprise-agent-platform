package com.enterprise.iqk.memory;

import com.enterprise.iqk.config.properties.MemoryProperties;
import com.enterprise.iqk.retrieval.LexicalMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Query-aware memory ranking. Relevance is the primary signal; recency and
 * confidence break ties and preserve a small amount of general context.
 *
 * 两个召回质量增强：
 * 1. 语义融合 —— 候选带缓存向量且查询向量可用时，词面相关性与余弦相似度
 *    按 blendWeight 加权融合，解决"换一种说法就召不回"的词面盲区；
 *    向量缺失（老数据 / embedding 失败）自动退化为纯词面，行为向前兼容。
 * 2. 近重复抑制 —— 同层候选内容规范化后相似度达阈值（互含或 bigram Jaccard）
 *    只保留排序靠前的一条：排序综合了相关性、近因与置信度，近重复对中
 *    内容相同时"分更高"通常即"更新更可信"，直接压制旧重复项。
 */
@Component
public class MemoryRelevanceScorer {
    private static final double FACT_RELEVANCE_FLOOR = 0.08;
    private static final double SHORT_RELEVANCE_FLOOR = 0.05;

    private final boolean dedupEnabled;
    private final double nearDuplicateThreshold;
    private final double semanticBlendWeight;

    /** 测试与默认行为：与 MemoryProperties 默认值保持一致。 */
    public MemoryRelevanceScorer() {
        this(new MemoryProperties());
    }

    @Autowired
    public MemoryRelevanceScorer(MemoryProperties properties) {
        this.dedupEnabled = properties.isDedupEnabled();
        this.nearDuplicateThreshold = properties.getNearDuplicateThreshold();
        this.semanticBlendWeight = properties.getSemanticBlendWeight();
    }

    public List<MemoryItemRecord> select(List<MemoryItemRecord> candidates,
                                         String query,
                                         String type,
                                         int limit) {
        return select(candidates, query, type, limit, null);
    }

    /**
     * 带语义上下文的召回打分：semantic 为 null 或候选缺向量时退化为纯词面。
     */
    public List<MemoryItemRecord> select(List<MemoryItemRecord> candidates,
                                         String query,
                                         String type,
                                         int limit,
                                         SemanticContext semantic) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        Set<String> queryTokens = LexicalMatcher.tokenize(query);
        String normalizedQuery = normalize(query);
        List<ScoredMemory> scored = candidates.stream()
                .map(item -> {
                    double relevance = relevance(item, queryTokens, normalizedQuery, semantic);
                    return new ScoredMemory(item,
                            score(item, relevance, type), relevance);
                })
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
                .toList();

        List<ScoredMemory> passing = scored.stream()
                .filter(item -> passesRelevanceFloor(item, queryTokens, type))
                .toList();
        if (!queryTokens.isEmpty() && passing.isEmpty() && !"fact".equals(type)) {
            return pickTopN(scored, limit);
        }
        return pickTopN(passing, limit);
    }

    /** 按排序取前 limit 条，途中做同层近重复抑制（保综合分更高的一条，不回填替补）。 */
    private List<MemoryItemRecord> pickTopN(List<ScoredMemory> scored, int limit) {
        List<MemoryItemRecord> kept = new ArrayList<>();
        for (ScoredMemory candidate : scored) {
            if (kept.size() >= limit) {
                break;
            }
            if (dedupEnabled && kept.stream().anyMatch(champion ->
                    nearDuplicate(champion, candidate.memory()))) {
                continue;
            }
            kept.add(candidate.memory());
        }
        return List.copyOf(kept);
    }

    private double score(MemoryItemRecord item, double relevance, String type) {
        double recency = recency(item.getCreatedAt(), halfLifeHours(type));
        double confidence = item.getConfidence() == null ? 0.5 : clamp01(item.getConfidence());

        if ("short".equals(type)) {
            return relevance * 0.60 + recency * 0.25 + confidence * 0.15;
        }
        return relevance * 0.70 + confidence * 0.25 + recency * 0.05;
    }

    private double relevance(MemoryItemRecord item,
                             Set<String> queryTokens,
                             String normalizedQuery,
                             SemanticContext semantic) {
        String content = item.getContent() == null ? "" : item.getContent();
        double relevance = LexicalMatcher.recallScore(queryTokens, LexicalMatcher.tokenize(content));
        if (StringUtils.hasText(normalizedQuery)) {
            String normalizedContent = normalize(content);
            if (normalizedContent.contains(normalizedQuery)) {
                relevance = Math.min(1.0, relevance + 0.25);
            }
        }
        float[] vector = semantic == null ? null : semantic.vectorOf(item.getMemoryId());
        if (vector != null && semantic.queryVector() != null) {
            double cosine = MemoryEmbeddingHelper.cosine(semantic.queryVector(), vector);
            return (1 - semanticBlendWeight) * relevance + semanticBlendWeight * clamp01(cosine);
        }
        return relevance;
    }

    /**
     * 近重复判定：去除全部空白并小写后，互为包含视为重复；
     * 否则按字符 bigram 的 Jaccard 相似度比对（中文无分词器也能工作）。
     */
    private boolean nearDuplicate(MemoryItemRecord champion, MemoryItemRecord candidate) {
        String a = normalizeForDedup(champion.getContent());
        String b = normalizeForDedup(candidate.getContent());
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.contains(b) || b.contains(a)) {
            return true;
        }
        return bigramJaccard(a, b) >= nearDuplicateThreshold;
    }

    private String normalizeForDedup(String content) {
        return content == null ? "" : content.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private double bigramJaccard(String a, String b) {
        if (a.length() < 2 || b.length() < 2) {
            return a.equals(b) ? 1.0 : 0.0;
        }
        Set<String> bigramsA = bigrams(a);
        Set<String> bigramsB = bigrams(b);
        long intersection = bigramsA.stream().filter(bigramsB::contains).count();
        long union = bigramsA.size() + bigramsB.size() - intersection;
        return union <= 0 ? 0.0 : (double) intersection / union;
    }

    private Set<String> bigrams(String text) {
        Set<String> bigrams = new HashSet<>();
        for (int i = 0; i + 1 < text.length(); i++) {
            bigrams.add(text.substring(i, i + 2));
        }
        return bigrams;
    }

    private boolean passesRelevanceFloor(ScoredMemory scored,
                                         Set<String> queryTokens,
                                         String type) {
        if (queryTokens.isEmpty() || "long".equals(type)) {
            return true;
        }
        double floor = "fact".equals(type) ? FACT_RELEVANCE_FLOOR : SHORT_RELEVANCE_FLOOR;
        return scored.relevance() >= floor;
    }

    private double recency(LocalDateTime createdAt, long halfLifeHours) {
        if (createdAt == null) {
            return 0.3;
        }
        long ageHours = Math.max(0, Duration.between(createdAt, LocalDateTime.now()).toHours());
        return Math.exp(-ageHours / (double) halfLifeHours);
    }

    private long halfLifeHours(String type) {
        if ("short".equals(type)) {
            return 12;
        }
        if ("fact".equals(type)) {
            return 24 * 365;
        }
        return 24 * 180;
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** 语义上下文：查询向量 + 按 memoryId 索引的候选缓存向量。 */
    public record SemanticContext(float[] queryVector, Map<String, float[]> vectorsByMemoryId) {

        float[] vectorOf(String memoryId) {
            return memoryId == null || vectorsByMemoryId == null
                    ? null : vectorsByMemoryId.get(memoryId);
        }
    }

    private record ScoredMemory(MemoryItemRecord memory, double score, double relevance) {
    }
}
