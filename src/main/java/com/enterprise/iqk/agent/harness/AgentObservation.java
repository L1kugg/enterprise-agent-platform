package com.enterprise.iqk.agent.harness;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动作观测：一次动作执行的标准化结果，回填到 ReAct 轨迹的 observation。
 * 无论哪个 runtime、成功还是失败，输出结构统一，模型与事件日志消费同一份。
 */
public record AgentObservation(
        /** 状态：success / error（缺省 success） */
        String status,
        /** 执行来源（builtin / mcp / workspace / policy / runtime） */
        String source,
        /** 结果载荷（可能被载荷消毒截断过） */
        Object payload,
        /** 失败原因（成功时为空串） */
        String errorMessage,
        /** 执行耗时毫秒 */
        long latencyMs
) {
    /** 规范化：空值兜底、耗时非负 */
    public AgentObservation {
        status = StringUtils.hasText(status) ? status : "success";
        source = StringUtils.hasText(source) ? source : "unknown";
        payload = payload == null ? Map.of() : payload;
        errorMessage = StringUtils.hasText(errorMessage) ? errorMessage : "";
        latencyMs = Math.max(0, latencyMs);
    }

    /** 成功观测工厂 */
    public static AgentObservation success(String source, Object payload, long latencyMs) {
        return new AgentObservation("success", source, payload, "", latencyMs);
    }

    /** 失败观测工厂（载荷为空 Map） */
    public static AgentObservation error(String source, String errorMessage, long latencyMs) {
        return new AgentObservation("error", source, Map.of(), errorMessage, latencyMs);
    }

    /** 是否成功（非 error 状态均视为成功） */
    public boolean successful() {
        return !"error".equals(status);
    }

    /** 展平为 Map：Map 载荷直接展开（status 优先取载荷内的），其余载荷挂到 data 键——供事件留痕与前端消费 */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (payload instanceof Map<?, ?> mapPayload) {
            Object payloadStatus = mapPayload.get("status");
            result.put("status", payloadStatus == null ? status : payloadStatus);
            mapPayload.forEach((key, value) -> {
                if (key != null && !"status".equals(String.valueOf(key))) {
                    result.put(String.valueOf(key), value);
                }
            });
        } else {
            result.put("status", status);
            result.put("data", payload);
        }
        result.put("source", source);
        result.put("latencyMs", latencyMs);
        if (StringUtils.hasText(errorMessage)) {
            result.put("message", errorMessage);
        }
        return result;
    }
}
