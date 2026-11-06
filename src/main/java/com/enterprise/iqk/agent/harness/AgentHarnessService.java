package com.enterprise.iqk.agent.harness;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Agent 执行挂载层（harness）入口：策略守卫 → 运行时分发 → 观测消毒 → 事件留痕。
 * 三层防线各自独立：守卫负责"该不该执行"，runtime 负责"怎么执行"，
 * 消毒器负责"结果能暴露多少"。任何一层失败都转为 error 观测而非异常上抛，
 * 保证 ReAct 循环拿到的一定是规整的 AgentObservation。
 */
@Service
@RequiredArgsConstructor
public class AgentHarnessService {
    /** 全部运行时实现，按注入顺序取第一个 supports 的 */
    private final List<AgentRuntime> runtimes;
    private final ActionPolicyGuard policyGuard;
    private final HarnessEventRecorder eventRecorder;
    private final HarnessPayloadSanitizer payloadSanitizer;
    private final ActionInputNormalizer inputNormalizer;
    private final HarnessAuditRecorder auditRecorder;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * 执行一个动作的完整链路：输入归一化 → 守卫拒绝 → error("policy")；
     * 无 runtime 接手 → error("runtime")；执行抛异常 → 兜底转 error 观测；
     * 每个结局都落一行动作审计（audit_log，覆盖 mcp/内建/平台/工作区全部动作
     * ——聊天路径此前只有平台工具在写，mcp/内建事后无法回查）；成功/失败的
     * 结果都要经载荷消毒并写事件后返回。
     */
    public AgentObservation execute(AgentAction action) {
        long startedNs = System.nanoTime();
        AgentAction normalized = inputNormalizer.normalize(action);
        ActionPolicyDecision decision = policyGuard.evaluate(normalized);
        if (!decision.allowed()) {
            AgentObservation observation = AgentObservation.error(
                    "policy",
                    decision.message(),
                    elapsedMs(startedNs)
            );
            auditRecorder.record(normalized, "policy", "policy_denied",
                    elapsedMs(startedNs), decision.message());
            eventRecorder.completed(normalized, observation);
            return observation;
        }

        AgentRuntime runtime = runtimes.stream()
                .filter(candidate -> candidate.supports(normalized.action()))
                .findFirst()
                .orElse(null);
        if (runtime == null) {
            AgentObservation observation = AgentObservation.error(
                    "runtime",
                    "no runtime for action: " + normalized.action(),
                    elapsedMs(startedNs)
            );
            auditRecorder.record(normalized, "runtime", "no_runtime",
                    elapsedMs(startedNs), "no runtime for action: " + normalized.action());
            eventRecorder.completed(normalized, observation);
            return observation;
        }

        eventRecorder.started(normalized, runtime.source());
        AgentObservation observation;
        try {
            observation = runtime.execute(normalized);
        } catch (RuntimeException ex) {
            observation = AgentObservation.error(runtime.source(), "action failed: " + ex.getMessage(), elapsedMs(startedNs));
        }
        observation = payloadSanitizer.limitObservation(observation);
        auditRecorder.record(normalized, runtime.source(), outcomeOf(observation),
                elapsedMs(startedNs), sanitizedInputSummary(normalized, decision.schema()));
        eventRecorder.completed(normalized, observation);
        return observation;
    }

    /** 审计结局：观测载荷内的 status 优先（如 pending_confirmation），其余取观测状态。 */
    private static String outcomeOf(AgentObservation observation) {
        if (observation.payload() instanceof Map<?, ?> payload
                && payload.get("status") instanceof String payloadStatus
                && org.springframework.util.StringUtils.hasText(payloadStatus)) {
            return payloadStatus;
        }
        return observation.status();
    }

    /** 审计摘要：守卫放行时携带 schema → 脱敏输入序列化（失败退化为动作名占位）。 */
    private String sanitizedInputSummary(AgentAction action, ActionSchema schema) {
        try {
            Map<String, Object> sanitized = schema == null ? action.actionInput()
                    : payloadSanitizer.sanitizeActionInput(action, schema);
            String json = objectMapper.writeValueAsString(sanitized);
            return json.length() <= 400 ? json : json.substring(0, 400) + "…";
        } catch (Exception ex) {
            return "{\"action\":\"" + action.action() + "\"}";
        }
    }

    /** 纳秒起点换算毫秒耗时 */
    private long elapsedMs(long startedNs) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
    }
}
