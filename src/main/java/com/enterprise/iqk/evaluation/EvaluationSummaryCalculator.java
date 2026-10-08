package com.enterprise.iqk.evaluation;

import com.enterprise.iqk.evaluation.vo.EvalMetricSummaryVO;
import org.springframework.stereotype.Component;

import java.util.List;

/** Calculates run-level metrics from persisted case results. */
@Component
public class EvaluationSummaryCalculator {
    private static final double PASS_THRESHOLD = 0.70;

    public EvalMetricSummaryVO summarize(List<EvalResultRecord> results) {
        List<EvalResultRecord> retrievalMetricResults = results.stream()
                .filter(item -> Boolean.TRUE.equals(item.getRetrievalMetricsApplicable()))
                .toList();
        int total = results.size();
        int passed = (int) results.stream()
                .filter(item -> "SUCCESS".equals(item.getStatus()))
                .filter(item -> item.getScore() != null && item.getScore() >= PASS_THRESHOLD)
                .count();
        double totalSafe = Math.max(1, total);
        return EvalMetricSummaryVO.builder()
                .totalCases(total)
                .passedCases(passed)
                .runScore(round(avg(results.stream().map(EvalResultRecord::getScore).toList())))
                .retrievalHitRate(round(avg(results.stream().map(EvalResultRecord::getRetrievalHit).toList())))
                .retrievalMetricsCases(retrievalMetricResults.size())
                .retrievalMetricLevel(retrievalMetricLevel(retrievalMetricResults))
                .recallAtKRate(round(avg(retrievalMetricResults.stream()
                        .map(EvalResultRecord::getRecallAtK).toList())))
                .mrrAtK(round(avg(retrievalMetricResults.stream()
                        .map(EvalResultRecord::getMrrAtK).toList())))
                .precisionAtKRate(round(avg(retrievalMetricResults.stream()
                        .map(EvalResultRecord::getPrecisionAtK).toList())))
                .citationCoverageRate(round(avg(results.stream()
                        .filter(item -> Boolean.TRUE.equals(item.getCitationCoverageApplicable()))
                        .map(EvalResultRecord::getCitationCoverage).toList())))
                .citationMarkerCoverageRate(round(avg(results.stream()
                        .map(EvalResultRecord::getCitationMarkerCoverage).toList())))
                .avgLatencyMs(round(avg(results.stream()
                        .map(item -> item.getLatencyMs() == null ? null : item.getLatencyMs().doubleValue())
                        .toList())))
                .failureRate(round(results.stream()
                        .filter(item -> !"SUCCESS".equals(item.getStatus())).count() / totalSafe))
                .build();
    }

    private String retrievalMetricLevel(List<EvalResultRecord> results) {
        if (results.isEmpty()) {
            return "none";
        }
        String first = emptyIfBlank(results.get(0).getRetrievalMetricLevel());
        boolean mixed = results.stream()
                .anyMatch(item -> !first.equals(emptyIfBlank(item.getRetrievalMetricLevel())));
        return mixed ? "mixed" : first;
    }

    private double avg(List<Double> values) {
        return values.stream().filter(value -> value != null)
                .mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private String emptyIfBlank(String value) {
        return value == null || value.isBlank() ? "" : value;
    }
}