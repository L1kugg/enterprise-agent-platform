package com.enterprise.iqk.memory;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryRelevanceScorerTest {

    private final MemoryRelevanceScorer scorer = new MemoryRelevanceScorer();

    @Test
    void relevantShortMemoryOutranksNewerIrrelevantMemory() {
        MemoryItemRecord relevant = memory("Java 微服务拆分原则", 3, 0.7);
        MemoryItemRecord irrelevant = memory("报销流程需要在周五前提交", 1, 0.95);

        List<MemoryItemRecord> selected = scorer.select(
                List.of(irrelevant, relevant), "Java 微服务怎么拆", "short", 1);

        assertThat(selected).containsExactly(relevant);
    }

    @Test
    void irrelevantHighConfidenceFactIsExcluded() {
        MemoryItemRecord relevant = memory("Keystone 三年 TCO 是 102 万元", 30, 0.75);
        MemoryItemRecord irrelevant = memory("消费级产品青藤幻觉率 7.3%", 1, 0.99);

        List<MemoryItemRecord> selected = scorer.select(
                List.of(irrelevant, relevant), "Keystone 三年 TCO 多少", "fact", 2);

        assertThat(selected).containsExactly(relevant);
    }

    @Test
    void longProfileAlwaysKeepsHighConfidenceBaseline() {
        MemoryItemRecord profile = memory("用户是 Java 后端开发者", 180, 0.9);

        List<MemoryItemRecord> selected = scorer.select(
                List.of(profile), "MySQL 索引怎么优化", "long", 5);

        assertThat(selected).containsExactly(profile);
    }

    private MemoryItemRecord memory(String content, int daysAgo, double confidence) {
        return MemoryItemRecord.builder()
                .content(content)
                .confidence(confidence)
                .createdAt(LocalDateTime.now().minusDays(daysAgo))
                .build();
    }
}
