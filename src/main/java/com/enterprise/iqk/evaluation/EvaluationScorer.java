package com.enterprise.iqk.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 评测打分器：对单个 case 计算 retrievalHit / citationCoverage / keywordScore /
 * answerFaithfulness 四项分值，加权出综合分（0.30 + 0.25 + 0.25 + 0.20）。
 * 期望列表为空按"无要求"给满分而非零分；命中禁用关键词视为幻觉信号，
 * 关键词分直接归零、忠实度封顶 0.2，保证坏答案不可能靠其他分项翻盘。
 */
@Component
@RequiredArgsConstructor
public class EvaluationScorer {
    private final ObjectMapper objectMapper;

    /**
     * 单 case 打分：关键词在"答案 + 证据"池里匹配，引用在引用池里匹配；
     * failed 直接把忠实度记 0。
     */
    public CaseScores scoreCase(EvalCaseRecord evalCase,
                                String answer,
                                List<String> citations,
                                List<String> evidence,
                                boolean failed) {
        List<String> expectedKeywords = readJsonList(evalCase.getExpectedKeywordsJson());
        List<String> expectedCitations = readJsonList(evalCase.getExpectedCitationsJson());
        List<String> forbiddenKeywords = readJsonList(evalCase.getForbiddenKeywordsJson());
        String answerPool = (emptyIfBlank(answer) + "\n" + String.join("\n", evidence)).toLowerCase(Locale.ROOT);
        String citationPool = String.join("\n", citations).toLowerCase(Locale.ROOT);

        double keywordScore = expectedKeywords.isEmpty()
                ? (StringUtils.hasText(answer) ? 1.0 : 0.0)
                : hitRate(expectedKeywords, answerPool);
        double citationCoverage = expectedCitations.isEmpty()
                ? 1.0
                : hitRate(expectedCitations, citationPool);
        boolean forbiddenHit = forbiddenKeywords.stream()
                .filter(StringUtils::hasText)
                .anyMatch(keyword -> answerPool.contains(keyword.toLowerCase(Locale.ROOT)));
        double retrievalHit = expectedCitations.isEmpty()
                ? (!citations.isEmpty() || !evidence.isEmpty() || keywordScore > 0 ? 1.0 : 0.0)
                : (citationCoverage > 0 ? 1.0 : 0.0);
        double faithfulness = failed ? 0.0 : scoreFaithfulness(answer, citations);
        if (forbiddenHit) {
            keywordScore = 0.0;
            faithfulness = Math.min(faithfulness, 0.2);
        }

        double score = round(0.30 * retrievalHit
                + 0.25 * citationCoverage
                + 0.25 * keywordScore
                + 0.20 * faithfulness);
        return new CaseScores(round(retrievalHit), round(citationCoverage), round(keywordScore), round(faithfulness), score);
    }

    /** 命中率：期望项在待检文本（已小写）中的出现比例。 */
    private double hitRate(List<String> expected, String actualLower) {
        if (expected == null || expected.isEmpty()) {
            return 1.0;
        }
        long hits = expected.stream()
                .filter(StringUtils::hasText)
                .filter(item -> actualLower.contains(item.toLowerCase(Locale.ROOT)))
                .count();
        return round(hits / (double) expected.size());
    }

    /** 忠实度：答案中 [n] 引用标记对引用列表的覆盖率；无答案 0 分，无引用给中位 0.5。 */
    private double scoreFaithfulness(String answer, List<String> citations) {
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

    /** 单 case 得分结果：四项分值 + 加权综合分。 */
    public record CaseScores(double retrievalHit,
                             double citationCoverage,
                             double keywordScore,
                             double answerFaithfulness,
                             double score) {
    }
}
