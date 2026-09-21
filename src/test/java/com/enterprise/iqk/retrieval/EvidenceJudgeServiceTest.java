package com.enterprise.iqk.retrieval;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 证据判分契约：时效度由 created_at 时间戳激活（此前 ingestion 不写时间戳恒中性 0.70），
 * 相关度加成的切词 CJK 感知（此前中文整句成单 token，命中检查必然落空）。
 */
class EvidenceJudgeServiceTest {

    private final EvidenceJudgeService service = new EvidenceJudgeService(new SimpleMeterRegistry());

    @Test
    void freshTimestampActivatesTimeliness() {
        ScoredDocument doc = doc(Map.of("created_at", System.currentTimeMillis()));

        EvidenceItem item = service.judge(List.of(doc), "q").get(0);

        assertThat(item.getTimelinessScore()).isEqualTo(1.0);
    }

    @Test
    void staleTimestampLowersTimeliness() {
        long oneYearAgoPlus = System.currentTimeMillis() - 400L * 24 * 60 * 60 * 1000;
        ScoredDocument doc = doc(Map.of("created_at", oneYearAgoPlus));

        EvidenceItem item = service.judge(List.of(doc), "q").get(0);

        assertThat(item.getTimelinessScore()).isEqualTo(0.5);
    }

    @Test
    void missingTimestampStaysNeutral() {
        EvidenceItem item = service.judge(List.of(doc(Map.of())), "q").get(0);

        assertThat(item.getTimelinessScore()).isEqualTo(0.70);
    }

    @Test
    void cjkQueryBoostsRelevanceByBigramHits() {
        ScoredDocument doc = doc(Map.of());
        doc.setFinalScore(0.30);

        EvidenceItem item = service.judge(List.of(doc), "怎么预约课程").get(0);

        // 查询 bigram 命中内容 2 个（预约/课程）→ 相关度 0.30 + 0.10
        assertThat(item.getRelevanceScore()).isCloseTo(0.40, within(1e-9));
    }

    @Test
    void sortsEvidenceByCompositeScoreDescending() {
        ScoredDocument low = doc(Map.of());
        low.setFinalScore(0.10);
        ScoredDocument high = doc(Map.of());
        high.setFinalScore(0.90);

        List<EvidenceItem> items = service.judge(List.of(low, high), "q");

        assertThat(items).extracting(EvidenceItem::getScore)
                .isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    private ScoredDocument doc(Map<String, Object> metadata) {
        return ScoredDocument.builder()
                .docId("doc-1")
                .sourceType("vector")
                .title("redis.pdf")
                .chunkId("chunk-0")
                .content("支持在线预约全部课程")
                .retrievalScore(0.8)
                .metadata(metadata)
                .build();
    }
}
