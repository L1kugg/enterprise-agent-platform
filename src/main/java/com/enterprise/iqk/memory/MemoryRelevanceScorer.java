package com.enterprise.iqk.memory;

import com.enterprise.iqk.retrieval.LexicalMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Query-aware memory ranking. Relevance is the primary signal; recency and
 * confidence break ties and preserve a small amount of general context.
 */
@Component
public class MemoryRelevanceScorer {
    private static final double FACT_RELEVANCE_FLOOR = 0.08;
    private static final double SHORT_RELEVANCE_FLOOR = 0.05;

    public List<MemoryItemRecord> select(List<MemoryItemRecord> candidates,
                                         String query,
                                         String type,
                                         int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        Set<String> queryTokens = LexicalMatcher.tokenize(query);
        String normalizedQuery = normalize(query);
        List<ScoredMemory> scored = candidates.stream()
                .map(item -> {
                    double relevance = relevance(item, queryTokens, normalizedQuery);
                    return new ScoredMemory(item,
                            score(item, relevance, type), relevance);
                })
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
                .toList();

        List<MemoryItemRecord> selected = scored.stream()
                .filter(item -> passesRelevanceFloor(item, queryTokens, type))
                .limit(limit)
                .map(ScoredMemory::memory)
                .toList();
        if (!queryTokens.isEmpty() && selected.isEmpty() && !"fact".equals(type)) {
            return scored.stream().limit(Math.min(2, limit)).map(ScoredMemory::memory).toList();
        }
        return selected;
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
                             String normalizedQuery) {
        String content = item.getContent() == null ? "" : item.getContent();
        double relevance = LexicalMatcher.recallScore(queryTokens, LexicalMatcher.tokenize(content));
        if (StringUtils.hasText(normalizedQuery)) {
            String normalizedContent = normalize(content);
            if (normalizedContent.contains(normalizedQuery)) {
                relevance = Math.min(1.0, relevance + 0.25);
            }
        }
        return relevance;
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

    private record ScoredMemory(MemoryItemRecord memory, double score, double relevance) {
    }
}
