package com.enterprise.iqk.service.agent;

import com.enterprise.iqk.domain.agent.AgentInvocationLogRecord;
import com.enterprise.iqk.mapper.AgentInvocationLogMapper;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.enterprise.iqk.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体观测看板服务（R2.2 读侧）：全部数字来自 agent_invocation_log 真实聚合，
 * 无调用数据时返回显式零值空态，不展示虚构数字。
 * 可见性：智能体必须当前租户可见（私有 ∪ 系统共享），日志统计再按当前租户圈定
 * （系统共享智能体的调用日志各租户只看自己的）。
 */
@Service
@RequiredArgsConstructor
public class PlatformAgentStatsService {

    /** 统计窗口天数上限。 */
    private static final int MAX_DAYS = 90;
    /** 错误 Top N 条数。 */
    private static final int TOP_ERRORS = 5;

    private final PlatformAssetMapper assetMapper;
    private final AgentInvocationLogMapper invocationLogMapper;

    /** 汇总统计 + 常见错误（一次看板首屏的两个聚合合并返回）。 */
    public Map<String, Object> stats(String tenantId, Long agentId, int days) {
        requireVisibleAgent(tenantId, agentId);
        LocalDateTime since = sinceOf(days);
        Map<String, Object> raw = invocationLogMapper.summarizeByAgent(tenantId, agentId, since);
        Map<String, Object> stats = new LinkedHashMap<>();
        long total = raw == null ? 0 : longOf(raw.get("total"));
        stats.put("days", clampDays(days));
        stats.put("total", total);
        stats.put("successCount", longOf(raw == null ? null : raw.get("success_count")));
        stats.put("blockedCount", longOf(raw == null ? null : raw.get("blocked_count")));
        stats.put("errorCount", longOf(raw == null ? null : raw.get("error_count")));
        stats.put("successRate", total == 0 ? 0.0 : round3(doubleOf(raw.get("success_count")) / total));
        stats.put("avgLatencyMs", longOf(raw == null ? null : raw.get("avg_latency_ms")));
        stats.put("maxLatencyMs", longOf(raw == null ? null : raw.get("max_latency_ms")));
        stats.put("inputTokens", longOf(raw == null ? null : raw.get("input_tokens")));
        stats.put("outputTokens", longOf(raw == null ? null : raw.get("output_tokens")));
        stats.put("fallbackCount", longOf(raw == null ? null : raw.get("fallback_count")));
        stats.put("firstInvocationAt", raw == null ? null : raw.get("first_invocation_at"));
        stats.put("lastInvocationAt", raw == null ? null : raw.get("last_invocation_at"));
        stats.put("topErrors", invocationLogMapper.topErrorsByAgent(tenantId, agentId, since, TOP_ERRORS)
                .stream()
                .map(row -> Map.of("summary", String.valueOf(row.get("error_summary")),
                        "count", longOf(row.get("cnt"))))
                .toList());
        return stats;
    }

    /** 按天趋势：SQL 有数的天直接用，缺天补零保证曲线连续。 */
    public List<Map<String, Object>> trend(String tenantId, Long agentId, int days) {
        requireVisibleAgent(tenantId, agentId);
        int safeDays = clampDays(days);
        Map<LocalDate, Map<String, Object>> byDay = new LinkedHashMap<>();
        for (Map<String, Object> row : invocationLogMapper.trendByAgent(tenantId, agentId, sinceOf(safeDays))) {
            Object dayValue = row.get("day");
            LocalDate day = dayValue instanceof java.sql.Date date ? date.toLocalDate()
                    : LocalDate.parse(String.valueOf(dayValue));
            byDay.put(day, row);
        }
        List<Map<String, Object>> points = new ArrayList<>();
        LocalDate cursor = LocalDate.now().minusDays(safeDays - 1L);
        for (int i = 0; i < safeDays; i++) {
            Map<String, Object> row = byDay.get(cursor);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", cursor.toString());
            point.put("total", row == null ? 0L : longOf(row.get("total")));
            point.put("successCount", row == null ? 0L : longOf(row.get("success_count")));
            point.put("blockedCount", row == null ? 0L : longOf(row.get("blocked_count")));
            point.put("errorCount", row == null ? 0L : longOf(row.get("error_count")));
            point.put("avgLatencyMs", row == null ? 0L : longOf(row.get("avg_latency_ms")));
            points.add(point);
            cursor = cursor.plusDays(1);
        }
        return points;
    }

    /** 调用日志分页：prompt/error 摘录本就是脱敏截断存储，这里只透出。 */
    public Map<String, Object> logs(String tenantId, Long agentId, int page, int pageSize,
                                    String status, String channel) {
        requireVisibleAgent(tenantId, agentId);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        String safeStatus = normalizeFilter(status, "SUCCESS", "BLOCKED", "ERROR");
        String safeChannel = normalizeFilter(channel, "platform", "debug");
        long total = invocationLogMapper.countByAgent(tenantId, agentId, safeStatus, safeChannel);
        List<AgentInvocationLogRecord> items = total == 0 ? List.of()
                : invocationLogMapper.pageByAgent(tenantId, agentId, safeStatus, safeChannel,
                        (long) (safePage - 1) * safeSize, safeSize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return result;
    }

    /** 可见性守卫：智能体必须当前租户可见（私有 ∪ 系统共享），否则 404。 */
    private void requireVisibleAgent(String tenantId, Long agentId) {
        if (agentId == null || agentId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid agent id");
        }
        if (assetMapper.findByIdAndTypeVisible(agentId, TenantContext.normalize(tenantId), "agents") == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "智能体不存在或无权访问");
        }
    }

    private LocalDateTime sinceOf(int days) {
        return LocalDateTime.now().minusDays(clampDays(days) - 1L).toLocalDate().atStartOfDay();
    }

    private int clampDays(int days) {
        return Math.max(1, Math.min(days, MAX_DAYS));
    }

    /** 过滤值白名单归一：非法值一律置 null（不过滤）。 */
    private String normalizeFilter(String value, String... allowed) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.trim();
        for (String option : allowed) {
            if (option.equalsIgnoreCase(candidate)) {
                return option;
            }
        }
        return null;
    }

    private long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString().trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private double doubleOf(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(value.toString().trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
