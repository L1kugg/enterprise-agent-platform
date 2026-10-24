package com.enterprise.iqk.service;

import com.enterprise.iqk.mapper.AnswerFeedbackMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务效果看板（管理视角）：聚合 answer_feedback 的窗口汇总、按日趋势与点踩原因 Top，
 * 把"员工用得怎么样"翻译成好评率/解决率代理与改进方向。照 PlatformAgentStatsService 惯例：
 * 窗口钳制、缺天补零、空态显式零值。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackInsightsService {

    private static final int MAX_DAYS = 90;
    private static final int TOP_REASONS = 5;

    private final AnswerFeedbackMapper answerFeedbackMapper;

    /** 看板聚合结果：summary + trend（缺天补零）+ topDislikeReasons。 */
    public Map<String, Object> insights(String tenantId, int days) {
        String normalizedTenant = tenantId == null || tenantId.isBlank() ? "public" : tenantId.trim();
        int window = clampDays(days);
        LocalDateTime since = LocalDate.now().minusDays(window - 1L).atStartOfDay();

        Map<String, Object> raw = answerFeedbackMapper.summarizeFeedback(normalizedTenant, since);
        long total = longOf(raw, "total");
        long positive = longOf(raw, "positive");
        long negative = longOf(raw, "negative");
        long neutral = longOf(raw, "neutral");
        long rated = positive + negative + neutral;
        // 好评率只按已评分口径：分母为 0 时显式 0.0（空态零值，不虚报 100%）
        double positiveRate = rated == 0 ? 0.0 : round3((double) positive / rated);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("positive", positive);
        summary.put("negative", negative);
        summary.put("neutral", neutral);
        summary.put("positiveRate", positiveRate);
        summary.put("days", window);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("trend", buildTrend(normalizedTenant, since, window));
        result.put("topDislikeReasons", buildTopReasons(normalizedTenant, since));
        return result;
    }

    /** 按日趋势补零：以"今天往前 window 天"为基准日历对齐，缺天显式 0。 */
    private List<Map<String, Object>> buildTrend(String tenantId, LocalDateTime since, int window) {
        Map<String, Map<String, Object>> byDay = new LinkedHashMap<>();
        for (Map<String, Object> row : answerFeedbackMapper.feedbackTrendByDay(tenantId, since)) {
            byDay.put(String.valueOf(row.get("day")), row);
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = window - 1; i >= 0; i--) {
            String day = today.minusDays(i).toString();
            Map<String, Object> row = byDay.get(day);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day);
            point.put("total", row == null ? 0L : longOf(row, "total"));
            point.put("positive", row == null ? 0L : longOf(row, "positive"));
            point.put("negative", row == null ? 0L : longOf(row, "negative"));
            trend.add(point);
        }
        return trend;
    }

    private List<Map<String, Object>> buildTopReasons(String tenantId, LocalDateTime since) {
        List<Map<String, Object>> rows = answerFeedbackMapper.topDislikeReasons(tenantId, since, TOP_REASONS);
        List<Map<String, Object>> reasons = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reason", String.valueOf(row.get("reason")));
            item.put("count", longOf(row, "count"));
            reasons.add(item);
        }
        return reasons;
    }

    private int clampDays(int days) {
        return Math.max(1, Math.min(MAX_DAYS, days));
    }

    private long longOf(Map<String, Object> raw, String key) {
        if (raw == null) {
            return 0L;
        }
        Object value = raw.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
