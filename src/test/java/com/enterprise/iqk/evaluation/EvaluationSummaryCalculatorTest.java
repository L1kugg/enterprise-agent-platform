package com.enterprise.iqk.evaluation;

import com.enterprise.iqk.evaluation.vo.EvalMetricSummaryVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationSummaryCalculatorTest {

    @Test
    void summarizeExcludesNotApplicableMetricsAndFailedCasesCannotPass() {
        EvaluationSummaryCalculator calculator = new EvaluationSummaryCalculator();

        List<EvalResultRecord> results = List.of(
                result("SUCCESS", 0.8, 0.5, true, 0.0),
                result("FAILED", 0.0, 1.0, true, 1.0),
                result("SUCCESS", 0.9, 0.0, false, 2.0)
        );

        EvalMetricSummaryVO metrics = calculator.summarize(results);

        assertThat(metrics.getTotalCases()).isEqualTo(3);
        assertThat(metrics.getPassedCases()).isEqualTo(2);
        assertThat(metrics.getRunScore()).isEqualTo(0.5667);
        assertThat(metrics.getCitationCoverageRate()).isEqualTo(0.75);
        assertThat(metrics.getRetrievalMetricsCases()).isEqualTo(2);
        assertThat(metrics.getRetrievalMetricLevel()).isEqualTo("chunk");
        assertThat(metrics.getRecallAtKRate()).isEqualTo(0.75);
        assertThat(metrics.getMrrAtK()).isEqualTo(0.5);
        assertThat(metrics.getPrecisionAtKRate()).isEqualTo(0.75);
        assertThat(metrics.getFailureRate()).isEqualTo(0.3333);
    }

    private EvalResultRecord result(String status,
                                    double score,
                                    double citationCoverage,
                                    boolean citationApplicable,
                                    double retrievalHit) {
        return EvalResultRecord.builder()
                .status(status)
                .score(score)
                .citationCoverage(citationCoverage)
                .citationCoverageApplicable(citationApplicable)
                .retrievalHit(retrievalHit)
                .retrievalMetricsApplicable(citationApplicable)
                .retrievalMetricLevel("chunk")
                .recallAtK(citationCoverage)
                .mrrAtK(0.5)
                .precisionAtK(citationCoverage)
                .citationMarkerCoverage(1.0)
                .latencyMs(1L)
                .build();
    }
}
