package com.enterprise.iqk.service;

import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.domain.vo.ReactTraceStepVO;
import com.enterprise.iqk.llm.ModelRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ReAct 响应格式化器：统一组装成功响应、SSE 事件帧与 JSON 序列化。
 * 引用/证据从轨迹 observation 中去重抽取（答案正文不再追加来源脚注——
 * 来源清单由前端据 citations 字段单独渲染，正文里再拼一份会重复展示）；
 * appendContext 负责观察上下文的滚动拼接；toJson 失败降级为固定 JSON，绝不打断流。
 */
@Component
@RequiredArgsConstructor
public class ReactResponseFormatter {
    private final ObjectMapper objectMapper;

    /** 组装成功响应：抽取 citations/evidence（正文不追加脚注）并附路由/实验信息。 */
    public ReactChatResponseVO success(String chatId,
                                       String answer,
                                       List<ReactTraceStepVO> trace,
                                       ModelRouter.ModelRouteDecision routeDecision,
                                       boolean fallback) {
        List<String> citations = extractTraceStrings(trace, "citations");
        List<String> evidence = extractTraceStrings(trace, "evidence");
        // 答案明确说"知识库无此内容"时不再挂引用，避免与检索 top-k 自相矛盾
        if (isNoHitAnswer(answer)) {
            citations = List.of();
            evidence = List.of();
        }
        return ReactChatResponseVO.builder()
                .ok(1)
                .msg("ok")
                .chatId(chatId)
                .answer(emptyIfBlank(answer))
                .fallback(fallback)
                .citations(citations)
                .evidence(evidence)
                .routeProfile(routeDecision == null ? "" : routeDecision.profile())
                .routeReason(routeDecision == null ? "" : routeDecision.reason())
                .routeCostTier(routeDecision == null ? "" : routeDecision.costTier())
                .experimentKey(routeDecision == null ? "" : routeDecision.experimentKey())
                .experimentVariant(routeDecision == null ? "" : routeDecision.experimentVariant())
                .experimentBucket(routeDecision == null ? null : routeDecision.experimentBucket())
                .trace(trace)
                .build();
    }

    /** 按 SSE 规范拼一个事件帧（event + data 两个行）。 */
    public String formatSse(String event, String data) {
        return "event: " + event + "\ndata: " + data + "\n\n";
    }

    /** 序列化为 JSON；失败返回固定占位串而非抛异常。 */
    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{\"message\":\"serialization_failed\"}";
        }
    }

    /** 观察上下文滚动拼接：在已有内容后追加一行 "action=..., observation=..."。 */
    public String appendContext(String origin, String action, Object observation) {
        StringBuilder builder = new StringBuilder(emptyIfBlank(origin));
        if (builder.length() > 0) {
            builder.append("\n");
        }
        return builder.append("action=").append(action).append(", observation=").append(toJson(observation)).toString();
    }

    /** 从轨迹各步 observation 的指定 key 去重抽取字符串列表。 */
    private static boolean isNoHitAnswer(String answer) {
        if (!StringUtils.hasText(answer)) return false;
        return answer.contains("暂无") || answer.contains("未收录")
                || answer.contains("没有在当前知识库") || answer.contains("未检索到");
    }

    private List<String> extractTraceStrings(List<ReactTraceStepVO> trace, String key) {
        if (trace == null || trace.isEmpty()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (ReactTraceStepVO step : trace) {
            if (step == null || !(step.getObservation() instanceof java.util.Map<?, ?> observation)) {
                continue;
            }
            Object raw = observation.get(key);
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    String normalized = emptyIfBlank(String.valueOf(item));
                    if (StringUtils.hasText(normalized)) {
                        values.add(normalized);
                    }
                }
            }
        }
        return List.copyOf(values);
    }

    /** null/空白统一返回空串。 */
    private String emptyIfBlank(String value) {
        return StringUtils.hasText(value) ? value : "";
    }
}
