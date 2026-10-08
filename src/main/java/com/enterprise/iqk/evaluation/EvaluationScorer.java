package com.enterprise.iqk.evaluation;

import com.enterprise.iqk.retrieval.RetrievalResultItem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 评测打分器：按实际配置项计算四类分值并动态加权归一化综合分。
 * 关键词与禁用词只评估最终答案；未配置项不参与均值；失败用例综合分归零。
 */
@Component
@RequiredArgsConstructor
public class EvaluationScorer {
    private static final double RETRIEVAL_WEIGHT = 0.30;
    private static final double CITATION_WEIGHT = 0.25;
    private static final double KEYWORD_WEIGHT = 0.25;
    private static final double CITATION_MARKER_WEIGHT = 0.20;

    private final ObjectMapper objectMapper;

    /**
     * 单 case 打分：关键词与禁用词只看最终答案；failed 综合分记 0。
     */
    public CaseScores scoreCase(EvalCaseRecord evalCase,
                                String answer,
                                List<String> citations,
                                List<String> evidence,
                                List<RetrievalResultItem> retrievalResults,
                                boolean failed) {
        List<String> expectedKeywords = readJsonList(evalCase.getExpectedKeywordsJson());
        List<String> expectedCitations = readJsonList(evalCase.getExpectedCitationsJson());
        List<String> forbiddenKeywords = readJsonList(evalCase.getForbiddenKeywordsJson());
        String answerLower = emptyIfBlank(answer).toLowerCase(Locale.ROOT);
        String citationPool = String.join("\n", citations).toLowerCase(Locale.ROOT);
        boolean keywordApplicable = !expectedKeywords.isEmpty() || !forbiddenKeywords.isEmpty();
        boolean citationApplicable = !expectedCitations.isEmpty();

        double keywordScore = 0.0;
        if (!expectedKeywords.isEmpty()) {
            keywordScore = hitRate(expectedKeywords, answerLower);
        } else if (keywordApplicable && StringUtils.hasText(answer)) {
            keywordScore = 1.0;
        }
        double citationCoverage = citationApplicable
                ? hitRate(expectedCitations, citationPool)
                : 0.0;
        boolean forbiddenHit = forbiddenKeywords.stream()
                .filter(StringUtils::hasText)
                .anyMatch(keyword -> answerLower.contains(keyword.toLowerCase(Locale.ROOT)));
        StandardRetrievalScores standardScores = standardRetrievalScores(evalCase, retrievalResults);
        double retrievalHit = standardScores.applicable()
                ? (standardScores.recallAtK() > 0 ? 1.0 : 0.0)
                : (!citations.isEmpty() || !evidence.isEmpty() ? 1.0 : 0.0);
        double markerCoverage = failed ? 0.0 : citationMarkerCoverage(answer, citations);
        if (forbiddenHit) {
            keywordScore = 0.0;
            markerCoverage = Math.min(markerCoverage, 0.2);
        }

        double citationWeight = citationApplicable ? CITATION_WEIGHT : 0.0;
        double keywordWeight = keywordApplicable ? KEYWORD_WEIGHT : 0.0;
        double weightSum = RETRIEVAL_WEIGHT + citationWeight + keywordWeight + CITATION_MARKER_WEIGHT;
        double weightedScore = RETRIEVAL_WEIGHT * retrievalHit
                + citationWeight * citationCoverage
                + keywordWeight * keywordScore
                + CITATION_MARKER_WEIGHT * markerCoverage;
        double score = failed ? 0.0 : round(weightedScore / weightSum);
        return new CaseScores(round(retrievalHit), round(citationCoverage), round(keywordScore),
                round(markerCoverage), score, keywordApplicable, citationApplicable,
                standardScores.applicable(), standardScores.level(), standardScores.recallAtK(),
                standardScores.mrrAtK(), standardScores.precisionAtK());
    }

    /** 命中率：期望项在待检文本（已小写）中的出现比例。 */
    private StandardRetrievalScores standardRetrievalScores(EvalCaseRecord evalCase,
                                                            List<RetrievalResultItem> results) {
        List<String> expectedChunks = readJsonList(evalCase.getExpectedChunkIdsJson());
        List<String> expectedDocuments = readJsonList(evalCase.getExpectedDocumentIdsJson());
        if (!expectedChunks.isEmpty()) {
            return calculate(expectedChunks, results == null ? List.of() : results, "chunk");
        }
        if (!expectedDocuments.isEmpty()) {
            return calculate(expectedDocuments, results == null ? List.of() : results, "document");
        }
        return new StandardRetrievalScores(false, "none", 0.0, 0.0, 0.0);
    }

    private StandardRetrievalScores calculate(List<String> expectedValues,
                                              List<RetrievalResultItem> results,
                                              String level) {
        Set<String> expected = normalizeExpected(expectedValues);
        Set<String> matched = new LinkedHashSet<>();
        double reciprocalRankSum = 0.0;
        int relevantRetrieved = 0;

        for (String value : expected) {
            for (int rank = 1; rank <= results.size(); rank++) {
                if (matches(value, results.get(rank - 1), level)) {
                    matched.add(value);
                    reciprocalRankSum += 1.0 / rank;
                    break;
                }
            }
        }
        for (RetrievalResultItem item : results) {
            if (expected.stream().anyMatch(value -> matches(value, item, level))) {
                relevantRetrieved++;
            }
        }

        double recall = expected.isEmpty() ? 0.0 : matched.size() / (double) expected.size();
        double mrr = expected.isEmpty() ? 0.0 : reciprocalRankSum / expected.size();
        double precision = results.isEmpty() ? 0.0 : relevantRetrieved / (double) results.size();
        return new StandardRetrievalScores(true, level, round(recall), round(mrr), round(precision));
    }

    private boolean matches(String expected, RetrievalResultItem item, String level) {
        if (item == null) {
            return false;
        }
        String title = normalize(item.title());
        String chunk = normalize(item.chunkId());
        String sourceTitle = normalize(item.sourceType() + ":" + item.title());
        String fullKey = normalize(item.sourceType() + ":" + item.title() + ":" + item.chunkId());
        if ("document".equals(level)) {
            return expected.equals(title) || expected.equals(sourceTitle);
        }
        return expected.equals(chunk) || expected.equals(title + ":" + chunk) || expected.equals(fullKey);
    }

    private Set<String> normalizeExpected(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        values.stream().filter(StringUtils::hasText).map(this::normalize).forEach(normalized::add);
        return normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private double hitRate(List<String> expected, String actualLower) {
        if (expected == null || expected.isEmpty()) {
            return 0.0;
        }
        long hits = expected.stream()
                .filter(StringUtils::hasText)
                .filter(item -> actualLower.contains(item.toLowerCase(Locale.ROOT)))
                .count();
        return round(hits / (double) expected.size());
    }

    /** 引用标记覆盖率：答案中 [n] 标记对返回引用列表的覆盖率；无引用给中位 0.5。 */
    private double citationMarkerCoverage(String answer, List<String> citations) {
        if (!StringUtils.hasText(answer)) {
            return 0.0;
        }
        if (citations == null || citations.isEmpty()) {
            return 0.5;
        }
        int markers = 0;
        for (int i = 1; i <= citations.size(); i++) {
            if (answer.contains("[" + i + "]")) {
                markers++;
            }
        }
        return round(Math.min(1.0, markers / (double) citations.size()));
    }

    private List<String> readJsonList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private String emptyIfBlank(String value) {
        return StringUtils.hasText(value) ? value : "";
    }

    private record StandardRetrievalScores(boolean applicable,
                                           String level,
                                           double recallAtK,
                                           double mrrAtK,
                                           double precisionAtK) {
    }

    /** 单 case 得分结果与标准检索指标；applicable 为 false 表示对应期望未配置。 */
    public record CaseScores(double retrievalHit,
                             double citationCoverage,
                             double keywordScore,
                             double citationMarkerCoverage,
                             double score,
                             boolean keywordScoreApplicable,
                             boolean citationCoverageApplicable,
                             boolean retrievalMetricsApplicable,
                             String retrievalMetricLevel,
                             double recallAtK,
                             double mrrAtK,
                             double precisionAtK) {
    }
}
