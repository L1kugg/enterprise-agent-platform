package com.enterprise.iqk.agent.harness;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

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

    /**
     * 执行一个动作的完整链路：守卫拒绝 → error("policy")；
     * 无 runtime 接手 → error("runtime")；执行抛异常 → 兜底转 error 观测；
     * 成功/失败的结果都要经载荷消毒并写事件后返回。
     */
    public AgentObservation execute(AgentAction action) {
        long startedNs = System.nanoTime();
        ActionPolicyDecision decision = policyGuard.evaluate(action);
        if (!decision.allowed()) {
            AgentObservation observation = AgentObservation.error(
                    "policy",
                    decision.message(),
                    elapsedMs(startedNs)
            );
            eventRecorder.completed(action, observation);
            return observation;
        }

        AgentRuntime runtime = runtimes.stream()
                .filter(candidate -> candidate.supports(action.action()))
                .findFirst()
                .orElse(null);
        if (runtime == null) {
            AgentObservation observation = AgentObservation.error(
                    "runtime",
                    "no runtime for action: " + action.action(),
                    elapsedMs(startedNs)
            );
            eventRecorder.completed(action, observation);
            return observation;
        }

        eventRecorder.started(action, runtime.source());
        AgentObservation observation;
        try {
            observation = runtime.execute(action);
        } catch (RuntimeException ex) {
            observation = AgentObservation.error(runtime.source(), "action failed: " + ex.getMessage(), elapsedMs(startedNs));
        }
        observation = payloadSanitizer.limitObservation(observation);
        eventRecorder.completed(action, observation);
        return observation;
    }

    /** 纳秒起点换算毫秒耗时 */
    private long elapsedMs(long startedNs) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
    }
}
