package com.enterprise.iqk.service.agent;

import com.enterprise.iqk.agent.harness.AgentAction;
import com.enterprise.iqk.agent.harness.AgentHarnessService;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactTraceStepVO;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.security.UserContext;
import com.enterprise.iqk.service.ReactDecisionParser.ReasonDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ReAct 两条链路（ReactAgentService / WorkflowReactAgentService）共用的执行支撑：
 * 记忆召回与注入段、动作执行（统一 taskId/stepId 留痕与绑定 KB 圈定）、轨迹 VO 组装、
 * 流式指标、以及租户/校验等纯工具方法。从两处逐字重复的实现收敛于此。
 */
@Component
@RequiredArgsConstructor
public class ReActSupport {

    private final AgentHarnessService agentHarnessService;
    private final MemoryService memoryService;
    private final MeterRegistry meterRegistry;

    // ── 记忆 ──────────────────────────────────────────────────────────────

    /** 记忆 user 键：认证主体，匿名回落 chatId。 */
    public String memoryUserKey(String chatId) {
        return UserContext.currentUserId(chatId);
    }

    /** 记忆召回：未绑定智能体走全量注入；智能体路径 short 层按 memoryTurns 裁剪（FR-R1-05）。
     *  任何失败返回 null，按"无记忆可用"降级，绝不中断 ReAct 主链路。 */
    public MemoryService.MemoryContextSnapshot recallMemory(String tenantId, String userKey,
                                                            String query, AgentChatRuntime.RunState runState) {
        try {
            if (runState == null) {
                return memoryService.buildContext(tenantId, userKey, query);
            }
            return memoryService.buildContext(tenantId, userKey, query, true, runState.config().memoryTurns());
        } catch (Exception ex) {
            return null;
        }
    }

    /** 记忆快照转 prompt 段；空快照用 "(none)" 占位，保持提示词结构稳定。 */
    public String memoryBlock(MemoryService.MemoryContextSnapshot snapshot) {
        return snapshot != null && StringUtils.hasText(snapshot.contextText())
                ? snapshot.contextText().trim() : "(none)";
    }

    /** memoryUsed 观测标签（快照为空时给空列表）。 */
    public List<String> memoryUsedLabels(MemoryService.MemoryContextSnapshot snapshot) {
        return snapshot == null ? List.of() : snapshot.usedLabels();
    }

    // ── 动作执行 ──────────────────────────────────────────────────────────

    /**
     * 把决策动作交给 AgentHarnessService（守卫/运行时/消毒/留痕），返回观测 Map。
     * 两链路统一：taskId/stepId 由调用方传入（留痕链路传真实 ID，对话链路传空串）；
     * agentId 供平台工具运行时校验绑定；绑定知识库列表供 rag_search 圈定召回范围（FR-R5-01）。
     */
    public Map<String, Object> executeAction(ReactChatRequestVO request,
                                             AgentChatRuntime.RunState runState,
                                             String action,
                                             Map<String, Object> actionInput,
                                             String tenantId,
                                             String taskId,
                                             String stepId) {
        return agentHarnessService.execute(new AgentAction(
                action,
                actionInput,
                request.getPrompt(),
                tenantId,
                request.getChatId(),
                request.getModelProfile(),
                taskId,
                stepId,
                runState == null || runState.config().agentId() == null ? 0L : runState.config().agentId(),
                false,
                runState == null ? List.of() : runState.config().knowledgeBaseIds()
        )).toMap();
    }

    // ── 轨迹与指标 ────────────────────────────────────────────────────────

    /** 拼单步轨迹 VO：elapsedMs 由调用方用 stepStartNs 掐表换算，覆盖本步规划 + 动作执行全程。 */
    public ReactTraceStepVO buildTraceStep(int step, ReasonDecision decision,
                                           Object observation, long elapsedMs) {
        return ReactTraceStepVO.builder()
                .step(step)
                .thought(decision.thought())
                .action(decision.action())
                .actionInput(decision.actionInput())
                .observation(observation)
                .elapsedMs(elapsedMs)
                .build();
    }

    /** 纳秒起点换算毫秒耗时。 */
    public long elapsedMs(long startedNs) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
    }

    /** 记录流式链路的总耗时 / 首 token 延迟 / 请求计数（按 outcome 打标），两链路同指标名便于对比。 */
    public void recordStreamMetrics(long startedNs, Long firstTokenLatencyMs, String outcome) {
        Timer.builder("react.stream.total.latency")
                .description("End-to-end latency for streamed ReAct requests")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(elapsedMs(startedNs), TimeUnit.MILLISECONDS);

        if (firstTokenLatencyMs != null) {
            Timer.builder("react.stream.first_token.latency")
                .description("Time-to-first-token latency for streamed ReAct requests")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(firstTokenLatencyMs, TimeUnit.MILLISECONDS);
        }

        Counter.builder("react.stream.requests")
                .description("Total streamed ReAct requests")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    // ── 纯工具 ────────────────────────────────────────────────────────────

    /** null/空白统一返回空串。 */
    public static String emptyIfBlank(String value) {
        return StringUtils.hasText(value) ? value : "";
    }

    /** 从 MDC 取归一化租户 ID。 */
    public static String currentTenantId() {
        return TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
    }

    /** 校验 prompt 与 chatId 必填，缺失抛 IllegalArgumentException。 */
    public static void validateRequest(ReactChatRequestVO request) {
        if (request == null || !StringUtils.hasText(request.getPrompt())) {
            throw new IllegalArgumentException("问题内容不能为空");
        }
        if (!StringUtils.hasText(request.getChatId())) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
    }
}
