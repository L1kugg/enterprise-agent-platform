package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.domain.AuditLog;
import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.AgentBindingMapper;
import com.enterprise.iqk.mapper.AuditLogMapper;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 平台工具运行时（R7.3）：处理 tool_call 动作——模型按名字调用**当前智能体绑定的**
 * 平台工具，FORM_API 走真实 HTTP、SQL 走只读执行器、CODE_API 显式拒绝。
 * 安全边界：工具必须（1）存在（2）启用（3）确在发起智能体的绑定列表内（agentId 反查，
 * 防越权调用未绑定工具）；工具级 executionConfirm=true 视为需人工确认，确认流程未接入前
 * 保守拒绝。失败一律转 error 观测不抛异常；返回值由 harness 统一消毒截断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformToolRuntime implements AgentRuntime {

    private final PlatformAssetMapper assetMapper;
    private final AgentBindingMapper bindingMapper;
    private final ToolInvocationService toolInvocationService;
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
        PlatformAsset tool = findBoundTool(action.agentId(), action.tenantId(), toolName);
        if (tool == null) {
            recordMetric(toolName, "not_bound");
            recordAudit(action, toolName, "not_bound");
            return AgentObservation.error(source(),
                    "工具未绑定或不存在：" + toolName + "（只能调用规划器提示词列出的绑定工具）",
                    elapsedMs(startedNs));
        }

        var config = toolInvocationService.parseToolConfig(tool);
        if (config.path("executionConfirm").asBoolean(false)) {
            log.info("平台工具需人工确认，确认流程未接入前保守拒绝: agentId={}, tool={}",
                    action.agentId(), toolName);
            recordMetric(toolName, "confirm_required");
            recordAudit(action, toolName, "confirm_required");
            return AgentObservation.error(source(),
                    "工具「" + toolName + "」配置为需人工确认，确认流程尚未接入，已拒绝执行",
                    elapsedMs(startedNs));
        }

        try {
            Map<String, Object> result = toolInvocationService.invoke(config, arguments);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", toolName);
            payload.put("toolId", tool.getId());
            payload.put("result", result);
            recordMetric(toolName, "success");
            recordAudit(action, toolName, "success");
            return AgentObservation.success(source(), payload, elapsedMs(startedNs));
        } catch (Exception ex) {
            log.warn("平台工具调用失败: agentId={}, tool={}, reason={}", action.agentId(), toolName, ex.toString());
            recordMetric(toolName, "error");
            recordAudit(action, toolName, "error");
            return AgentObservation.error(source(),
                    "工具调用失败：" + rootMessage(ex), elapsedMs(startedNs));
        }
    }

    /**
     * 在发起智能体的绑定列表内按名字找工具：
     * agent_tool_binding（tenant+agent 界内）→ 逐个 mapper 原始读取（不过脱敏，credential 保真）→
     * 名字精确匹配（忽略大小写）且 ENABLED。绑定行为空或全不匹配返回 null（上层报未绑定）。
     */
    private PlatformAsset findBoundTool(Long agentId, String tenantId, String toolName) {
        if (agentId == null || agentId <= 0 || !StringUtils.hasText(toolName)) {
            return null;
        }
        List<Long> toolIds;
        try {
            toolIds = bindingMapper.findByAgent("agent_tool_binding", tenantId, agentId)
                    .stream().map(row -> row.getResourceId()).toList();
        } catch (Exception ex) {
            log.warn("读取智能体工具绑定失败: agentId={}, reason={}", agentId, ex.toString());
            return null;
        }
        for (Long toolId : toolIds) {
            PlatformAsset tool = assetMapper.findByIdAndTypeVisible(toolId, tenantId, "tools");
            if (tool == null || !"ENABLED".equalsIgnoreCase(tool.getStatus())) {
                continue;
            }
            if (toolName.equalsIgnoreCase(tool.getName())) {
                return tool;
            }
        }
        return null;
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
    private void recordAudit(AgentAction action, String toolName, String outcome) {
        try {
            auditLogMapper.insert(AuditLog.builder()
                    .requestId(MDC.get("request_id"))
                    .traceId(MDC.get("trace_id"))
                    .tenantId(action.tenantId())
                    .userIdentity(action.chatId())
                    .method("TOOL_CALL")
                    .path("/agent-runtime/tool-call/" + toolName)
                    .statusCode("success".equals(outcome) ? 200 : 500)
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
