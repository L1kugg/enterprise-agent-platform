package com.enterprise.iqk.agent.harness;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 载荷消毒器：动作输入与观测结果在进入事件日志/返回模型前的统一脱敏与截断。
 * 两套预算：回填给模型的观测（字符串 4000 字符）宽，落事件日志的摘要（600 字符）紧。
 * 敏感字段（schema 声明 + password/token/apikey 等关键词命中）一律替换为 [REDACTED]。
 */
@Component
public class HarnessPayloadSanitizer {
    /** 观测载荷中单个字符串的最大长度（回填 ReAct 用，预算较宽） */
    private static final int MAX_STRING_LENGTH = 4_000;
    /** 事件日志中单个字符串的最大长度（留痕用，预算较紧） */
    private static final int MAX_EVENT_STRING_LENGTH = 600;
    private static final int MAX_COLLECTION_ITEMS = 30;
    private static final int MAX_MAP_ENTRIES = 60;
    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "password", "secret", "token", "apikey", "api_key", "contactinfo", "authorization"
    );

    /** 脱敏动作输入：敏感键→[REDACTED]，其余值截断到事件预算——供 ACTION_STARTED 事件使用 */
    public Map<String, Object> sanitizeActionInput(AgentAction action, ActionSchema schema) {
        if (action == null) {
            return Map.of();
        }
        Set<String> schemaSensitive = schema == null ? Set.of() : schema.sensitiveFields();
        Map<String, Object> result = new LinkedHashMap<>();
        action.actionInput().forEach((key, value) -> {
            if (isSensitive(key, schemaSensitive)) {
                result.put(key, "[REDACTED]");
            } else {
                result.put(key, limit(value, MAX_EVENT_STRING_LENGTH));
            }
        });
        return result;
    }

    /** 观测摘要：Map 载荷只保留 payloadKeys 与 message，不落具体数据——控制事件日志体积 */
    public Map<String, Object> summarizeObservation(AgentObservation observation) {
        if (observation == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source", observation.source());
        summary.put("status", observation.status());
        summary.put("latencyMs", observation.latencyMs());
        if (observation.payload() instanceof Map<?, ?> payload) {
            summary.put("payloadKeys", payload.keySet().stream()
                    .map(String::valueOf)
                    .limit(MAX_COLLECTION_ITEMS)
                    .toList());
            Object message = payload.get("message");
            if (message != null) {
                summary.put("message", truncate(String.valueOf(message), MAX_EVENT_STRING_LENGTH));
            }
        }
        if (!observation.errorMessage().isBlank()) {
            summary.put("errorMessage", truncate(observation.errorMessage(), MAX_EVENT_STRING_LENGTH));
        }
        return summary;
    }

    /** 限制观测载荷：递归截断字符串/集合/Map 后重建观测（回填模型前调用） */
    public AgentObservation limitObservation(AgentObservation observation) {
        if (observation == null) {
            return null;
        }
        return new AgentObservation(
                observation.status(),
                observation.source(),
                limit(observation.payload(), MAX_STRING_LENGTH),
                truncate(observation.errorMessage(), MAX_EVENT_STRING_LENGTH),
                observation.latencyMs()
        );
    }

    /** 递归限流：字符串截断、Map 超 60 项打标截断、集合超 30 项打标截断，其余原样返回 */
    private Object limit(Object value, int stringLength) {
        if (value instanceof String text) {
            return truncate(text, stringLength);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= MAX_MAP_ENTRIES) {
                    result.put("_truncated", true);
                    break;
                }
                result.put(String.valueOf(entry.getKey()), limit(entry.getValue(), stringLength));
            }
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            int count = 0;
            for (Object item : iterable) {
                if (count++ >= MAX_COLLECTION_ITEMS) {
                    result.add(Map.of("_truncated", true));
                    break;
                }
                result.add(limit(item, stringLength));
            }
            return result;
        }
        return value;
    }

    /** 是否敏感：schema 显式声明，或键名命中敏感关键词（大小写不敏感、子串匹配） */
    private boolean isSensitive(String key, Set<String> schemaSensitive) {
        if (schemaSensitive.contains(key)) {
            return true;
        }
        String normalized = key.toLowerCase();
        return SENSITIVE_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    /** 截断字符串：超长部分丢弃并追加 "...[truncated]" 标记 */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...[truncated]";
    }
}
