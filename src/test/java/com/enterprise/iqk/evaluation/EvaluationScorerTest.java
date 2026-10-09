package com.enterprise.iqk.evaluation;

import com.enterprise.iqk.retrieval.RetrievalResultItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationScorerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EvaluationScorer scorer = new EvaluationScorer(objectMapper);

    @Test
    void shouldScoreCitationAndKeywordCoverage() throws Exception {
        EvalCaseRecord evalCase = EvalCaseRecord.builder()
                .expectedKeywordsJson(objectMapper.writeValueAsString(List.of("高温", "风险")))
                .expectedCitationsJson(objectMapper.writeValueAsString(List.of("heat-policy")))
                .forbiddenKeywordsJson(objectMapper.writeValueAsString(List.of("编造")))
                .build();

        EvaluationScorer.CaseScores scores = scorer.scoreCase(
                evalCase,
                "高温风险处置建议见引用 [1]",
                List.of("vector:heat-policy:chunk-1"),
                List.of("高温风险包括中暑、脱水与慢病加重。"),
                List.of(new RetrievalResultItem("vector", "heat-policy", "chunk-1", 0.9)),
                false
        );

        assertThat(scores.retrievalHit()).isEqualTo(1.0);
        assertThat(scores.citationCoverage()).isEqualTo(1.0);
        assertThat(scores.keywordScore()).isEqualTo(1.0);
        assertThat(scores.citationMarkerCoverage()).isEqualTo(1.0);
        assertThat(scores.score()).isEqualTo(1.0);
    }

    @Test
    void shouldPenalizeForbiddenKeywords() throws Exception {
        EvalCaseRecord evalCase = EvalCaseRecord.builder()
                .expectedKeywordsJson(objectMapper.writeValueAsString(List.of("高温")))
                .expectedCitationsJson(objectMapper.writeValueAsString(List.of()))
                .forbiddenKeywordsJson(objectMapper.writeValueAsString(List.of("编造")))
                .build();

        EvaluationScorer.CaseScores scores = scorer.scoreCase(
                evalCase,
                "这里编造一个高温结论。",
                List.of(),
                List.of(),
                List.of(),
                false
        );

        assertThat(scores.keywordScore()).isEqualTo(0.0);
        assertThat(scores.citationMarkerCoverage()).isLessThanOrEqualTo(0.2);
        assertThat(scores.score()).isLessThan(0.70);
    }

    @Test
    void shouldNotCountEvidenceOnlyKeywordsAsAnswerCoverage() throws Exception {
        EvalCaseRecord evalCase = EvalCaseRecord.builder()
                .expectedKeywordsJson(objectMapper.writeValueAsString(List.of("102")))
                .expectedCitationsJson(objectMapper.writeValueAsString(List.of("report")))
                .build();

        EvaluationScorer.CaseScores scores = scorer.scoreCase(
                evalCase, "资料中没有明确说明。", List.of("vector:report:chunk-1"),
                List.of("项目预算为 102 万元。"), List.of(), false);

        assertThat(scores.keywordScore()).isZero();
        assertThat(scores.keywordScoreApplicable()).isTrue();
        assertThat(scores.citationCoverage()).isEqualTo(1.0);
    }

    @Test
    void shouldNotPenalizeForbiddenWordsOnlyPresentInEvidence() throws Exception {
        EvalCaseRecord evalCase = EvalCaseRecord.builder()
                .expectedKeywordsJson(objectMapper.writeValueAsString(List.of("102")))
                .expectedCitationsJson(objectMapper.writeValueAsString(List.of("report")))
                .forbiddenKeywordsJson(objectMapper.writeValueAsString(List.of("错误结论")))
                .build();

        EvaluationScorer.CaseScores scores = scorer.scoreCase(
                evalCase, "预算为 102 万元 [1]。", List.of("vector:report:chunk-1"),
                List.of("错误结论属于另一份干扰文档。"),
                List.of(new RetrievalResultItem("vector", "report", "chunk-1", 0.9)), false);

        assertThat(scores.keywordScore()).isEqualTo(1.0);
        assertThat(scores.citationMarkerCoverage()).isEqualTo(1.0);
        assertThat(scores.score()).isEqualTo(1.0);
    }

    @Test
    void shouldZeroFailedCaseScoreEvenWhenRetrievalLooksGood() throws Exception {
        EvalCaseRecord evalCase = EvalCaseRecord.builder()
                .expectedKeywordsJson(objectMapper.writeValueAsString(List.of("102")))
                .expectedCitationsJson(objectMapper.writeValueAsString(List.of("report")))
                .build();

        EvaluationScorer.CaseScores scores = scorer.scoreCase(
                evalCase, "预算为 102 万元 [1]。", List.of("vector:report:chunk-1"),
                List.of("项目预算为 102 万元。"), List.of(), true);

        assertThat(scores.score()).isZero();
    }

    @Test
    void shouldExcludeUnconfiguredScoreDimensions() throws Exception {
        EvalCaseRecord evalCase = EvalCaseRecord.builder().build();

        EvaluationScorer.CaseScores scores = scorer.scoreCase(
                evalCase, "已返回回答。", List.of(), List.of("evidence"), List.of(), false);

        assertThat(scores.keywordScoreApplicable()).isFalse();
        assertThat(scores.citationCoverageApplicable()).isFalse();
        assertThat(scores.score()).isEqualTo(0.8);
    }
    @Test
    void shouldCalculateDocumentAndChunkRetrievalMetrics() throws Exception {
        EvalCaseRecord evalCase = EvalCaseRecord.builder()
                .expectedDocumentIdsJson(objectMapper.writeValueAsString(List.of("report-a.md")))
                .expectedChunkIdsJson(objectMapper.writeValueAsString(List.of("report-a.md:chunk-2")))
                .build();
        List<RetrievalResultItem> results = List.of(
                new RetrievalResultItem("keyword", "noise.md", "chunk-9", 0.9),
                new RetrievalResultItem("vector", "report-a.md", "chunk-2", 0.8),
                new RetrievalResultItem("vector", "report-a.md", "chunk-1", 0.7)
        );

        EvaluationScorer.CaseScores scores = scorer.scoreCase(evalCase, "答案", List.of(), List.of(), results, false);

        assertThat(scores.retrievalMetricsApplicable()).isTrue();
        assertThat(scores.retrievalMetricLevel()).isEqualTo("chunk");
        assertThat(scores.recallAtK()).isEqualTo(1.0);
        assertThat(scores.mrrAtK()).isEqualTo(0.5);
        assertThat(scores.precisionAtK()).isEqualTo(0.3333);
    }

    @Test
    void shouldFallbackToExpectedCitationsForLegacyDatasets() throws Exception {
        EvalCaseRecord evalCase = EvalCaseRecord.builder()
                .expectedCitationsJson(objectMapper.writeValueAsString(List.of("report-a")))
                .build();
        List<RetrievalResultItem> results = List.of(
                new RetrievalResultItem("keyword", "noise.md", "chunk-1", 0.9),
                new RetrievalResultItem("vector", "report-a.md", "chunk-2", 0.8)
        );

        EvaluationScorer.CaseScores scores = scorer.scoreCase(
                evalCase, "答案", List.of(), List.of(), results, false);

        assertThat(scores.retrievalMetricsApplicable()).isTrue();
        assertThat(scores.retrievalMetricLevel()).isEqualTo("citation");
        assertThat(scores.recallAtK()).isEqualTo(1.0);
        assertThat(scores.mrrAtK()).isEqualTo(0.5);
        assertThat(scores.precisionAtK()).isEqualTo(0.5);
    }
}
