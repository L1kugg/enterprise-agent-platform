package com.enterprise.iqk.retrieval;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 词面匹配工具的切词与打分契约：
 * 中文连续段必须切成 2-gram（否则整句成单 token，中文查询永远匹配不上）；
 * 打分分母必须是查询 token 数（否则长文档分数必然趋零）。
 */
class LexicalMatcherTest {

    @Test
    void chineseRunIsSplitIntoBigrams() {
        Set<String> tokens = LexicalMatcher.tokenize("课程预约怎么办理");

        // 8 字整句切成 7 个 bigram，"预约" 这样的词级片段可以被查询命中
        assertThat(tokens).doesNotContain("课程预约怎么办理");
        assertThat(tokens).contains("预约", "课程");
        assertThat(tokens).hasSize(7);
    }

    @Test
    void mixedLatinAndCjkKeepsLatinWhole() {
        Set<String> tokens = LexicalMatcher.tokenize("Redis缓存穿透");

        // 拉丁段整体保留（术语精确匹配的载体），CJK 段切 bigram
        assertThat(tokens).contains("redis");
        assertThat(tokens).contains("缓存", "穿透");
        assertThat(tokens).doesNotContain("redis缓存穿透");
    }

    @Test
    void singleCjkCharStaysAsToken() {
        assertThat(LexicalMatcher.tokenize("课")).containsExactly("课");
    }

    @Test
    void recallScoreDenominatorIsQuerySizeNotTargetSize() {
        Set<String> query = Set.of("redis", "缓存", "穿透");
        // 目标集合很大（长文档场景）：召回分不应被文档长度稀释
        Set<String> longTarget = new HashSet<>(Set.of("redis", "缓存"));
        for (int i = 0; i < 50; i++) {
            longTarget.add("填充词" + i);
        }

        // 3 个查询词命中 2 个 → 2/3，与目标集合大小无关
        assertThat(LexicalMatcher.recallScore(query, longTarget)).isCloseTo(2.0 / 3.0, within(1e-9));
    }

    @Test
    void emptyQueryOrTargetScoresZero() {
        assertThat(LexicalMatcher.recallScore(Set.of(), Set.of("a"))).isZero();
        assertThat(LexicalMatcher.recallScore(Set.of("a"), Set.of())).isZero();
        assertThat(LexicalMatcher.tokenize("  ")).isEmpty();
    }
}
