package com.enterprise.iqk.memory;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    @Test
    void nearDuplicateMemoriesKeepOnlyTheHigherScoredOne() {
        // 同一画像换个措辞重复沉淀：词面高度重叠，近重复抑制只留综合分更高（更新）的一条
        MemoryItemRecord older = memory("画像: 用户是初级 Java 开发者，正在学习微服务", 30, 0.85);
        MemoryItemRecord newer = memory("画像: 用户是初级 Java 开发者，正在学习微服务架构", 1, 0.85);
        MemoryItemRecord distinct = memory("画像: 用户偏好中文简洁回答", 10, 0.85);

        List<MemoryItemRecord> selected = scorer.select(
                List.of(older, newer, distinct), "微服务架构学习建议", "long", 3);

        assertThat(selected).contains(newer).doesNotContain(older).hasSize(2);
    }

    @Test
    void containmentCountsAsNearDuplicate() {
        // 一条内容完全包含另一条（截断/扩写关系）：视为重复，不占召回名额
        MemoryItemRecord shortOne = memory("用户偏好中文回答", 5, 0.85);
        MemoryItemRecord longOne = memory("用户偏好中文回答，技术名词保留英文", 4, 0.85);

        List<MemoryItemRecord> selected = scorer.select(
                List.of(shortOne, longOne), "", "long", 5);

        assertThat(selected).containsExactly(longOne);
    }

    @Test
    void semanticVectorLiftsParaphrasedMatchAboveLexicalMiss() {
        // "出差住一晚单位给报多少钱" 与事实内容 2-gram 零重叠：纯词面召不回（fact 层有门槛无兜底）
        MemoryItemRecord fact = memory("差旅报销标准：交通实报实销，住宿每晚上限 400 元", 2, 0.8);
        assertThat(scorer.select(List.of(fact), "出差住一晚单位给报多少钱", "fact", 1))
                .describedAs("纯词面基线：无 token 重叠时 fact 层被门槛滤掉")
                .isEmpty();

        // 语义向量把高余弦相似融入 relevance，跨措辞召回成立
        MemoryItemRecord scored = fact.toBuilder().memoryId("mem-fact-1").build();
        float[] queryVector = {1f, 0f};
        float[] factVector = {0.9f, 0.1f};
        MemoryRelevanceScorer.SemanticContext semantic = new MemoryRelevanceScorer.SemanticContext(
                queryVector, Map.of("mem-fact-1", factVector));

        assertThat(scorer.select(List.of(scored), "出差住一晚单位给报多少钱", "fact", 1, semantic))
                .containsExactly(scored);
    }

    @Test
    void semanticContextWithoutCandidateVectorKeepsLexicalBehavior() {
        // 候选没有缓存向量（老数据/embedding 失败）：语义上下文存在也不影响词面行为
        MemoryItemRecord relevant = memory("Keystone 三年 TCO 是 102 万元", 30, 0.75);
        MemoryRelevanceScorer.SemanticContext semantic = new MemoryRelevanceScorer.SemanticContext(
                new float[]{1f, 0f}, Map.of());

        List<MemoryItemRecord> selected = scorer.select(
                List.of(relevant), "Keystone 三年 TCO 多少", "fact", 2, semantic);

        assertThat(selected).containsExactly(relevant);
    }

    private MemoryItemRecord memory(String content, int daysAgo, double confidence) {
        return MemoryItemRecord.builder()
                .content(content)
                .confidence(confidence)
                .createdAt(LocalDateTime.now().minusDays(daysAgo))
                .build();
    }
}
