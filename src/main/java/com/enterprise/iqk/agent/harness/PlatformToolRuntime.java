package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.domain.AuditLog;
import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.AuditLogMapper;
import com.enterprise.iqk.service.platform.PlatformToolInvoker;
import com.enterprise.iqk.service.platform.ToolInvocationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 平台工具运行时（R7.3）：处理 tool_call 动作——模型按名字调用**当前智能体绑定的**
 * 平台工具，FORM_API 走真实 HTTP、SQL 走只读执行器、CODE_API 走 GraalJS 纯计算沙箱。
 * 安全边界：工具必须（1）存在（2）启用（3）确在发起智能体的绑定列表内（agentId 反查，
 * 防越权调用未绑定工具）；工具级 executionConfirm=true 时**挂起待人工确认**——
 * 创建确认任务返回 pending_confirmation 观测，用户在聊天界面审批后才执行，
 * 模型不会在未获确认时拿到执行结果。失败一律转 error 观测不抛异常；
 * 返回值由 harness 统一消毒截断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformToolRuntime implements AgentRuntime {

    private final PlatformToolInvoker toolInvoker;
    private final ToolInvocationService toolInvocationService;
    private final com.enterprise.iqk.service.platform.PlatformToolConfirmationService confirmationService;
    private final AuditLogMapper auditLogMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public String source() {
        return "tool";
    }

    @Override
    public boolean supports(String action) {
        return "tool_call".equals(action);
    }

    @Override
    public AgentObservation execute(AgentAction action) {
        long startedNs = System.nanoTime();
        String toolName = stringVal(action.actionInput(), "tool");
        Map<String, Object> arguments = mapVal(action.actionInput().get("arguments"));

        // 绑定校验：发起智能体绑定的工具里找这个名字（默认助手/未带 agentId 一律无绑定可用）
        PlatformAsset tool = toolInvoker.findBoundTool(action.agentId(), action.tenantId(), toolName);
        if (tool == null) {
            recordMetric(toolName, "not_bound");
            recordAudit(action, toolName, "not_bound", elapsedMs(startedNs));
            return AgentObservation.error(source(),
                    "工具未绑定或不存在：" + toolName + "（只能调用规划器提示词列出的绑定工具）",
                    elapsedMs(startedNs));
        }

        var config = toolInvocationService.parseToolConfig(tool);
        if (config.path("executionConfirm").asBoolean(false)) {
            var pending = confirmationService.create(action.agentId(), action.tenantId(),
                    action.chatId(), tool, arguments);
            log.info("平台工具需人工确认，已挂起待审: agentId={}, tool={}, confirmationId={}",
                    action.agentId(), toolName, pending.getId());
            recordMetric(toolName, "confirm_pending");
            recordAudit(action, toolName, "confirm_pending", elapsedMs(startedNs));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "pending_confirmation");
            payload.put("confirmationId", pending.getId());
            // 回显绑定工具的规范名（模型传入名可能带类型后缀，已宽容匹配）
            payload.put("tool", tool.getName());
            payload.put("toolId", tool.getId());
            payload.put("arguments", arguments);
            payload.put("expiresAt", pending.getExpiresAt().toString());
            payload.put("message", "工具「" + toolName + "」需用户确认后才执行，请在聊天界面审批或取消");
            return AgentObservation.success(source(), payload, elapsedMs(startedNs));
        }

        try {
            Map<String, Object> result = toolInvoker.invoke(tool, config, arguments);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", tool.getName());
            payload.put("toolId", tool.getId());
            payload.put("result", result);
            recordMetric(toolName, "success");
            recordAudit(action, toolName, "success", elapsedMs(startedNs));
            return AgentObservation.success(source(), payload, elapsedMs(startedNs));
        } catch (Exception ex) {
            log.warn("平台工具调用失败: agentId={}, tool={}, reason={}", action.agentId(), toolName, ex.toString());
            recordMetric(toolName, "error");
            recordAudit(action, toolName, "error", elapsedMs(startedNs));
            return AgentObservation.error(source(),
                    "工具调用失败：" + rootMessage(ex), elapsedMs(startedNs));
        }
    }

    /** 计数器：按工具与结局打标（not_bound/confirm_required 属策略拒绝面）。 */
    private void recordMetric(String toolName, String outcome) {
        Counter.builder("tool.call.requests")
                .description("Platform tool invocations from agent tool_call action")
                .tag("tool", toolName)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    /** 聊天路径留痕（R7.3"工具结果写入调用日志"）：每次执行落一条审计；失败只告警不影响执行。 */
    private void recordAudit(AgentAction action, String toolName, String outcome, long latencyMs) {
        try {
            auditLogMapper.insert(AuditLog.builder()
                    .requestId(MDC.get("request_id"))
                    .traceId(MDC.get("trace_id"))
                    .tenantId(action.tenantId())
                    .userIdentity(action.chatId())
                    .method("TOOL_CALL")
                    .path("/agent-runtime/tool-call/" + toolName)
                    .statusCode("success".equals(outcome) ? 200 : 500)
                    .durationMs(latencyMs)
                    .chatId(action.chatId())
                    .extraPayload("agentId=%s,outcome=%s".formatted(action.agentId(), outcome))
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception ex) {
            log.warn("tool_call 审计写入失败（不影响执行）: tool={}, reason={}", toolName, ex.toString());
        }
    }

    /** 非 Map 输入统一降级为空 Map。 */
    private Map<String, Object> mapVal(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String stringVal(Map<String, Object> input, String key) {
        Object raw = input.get(key);
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private long elapsedMs(long startedNs) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
    }

    private static String rootMessage(Throwable ex) {
        String message = ex.getMessage();
        return StringUtils.hasText(message) ? message : ex.getClass().getSimpleName();
    }
}
