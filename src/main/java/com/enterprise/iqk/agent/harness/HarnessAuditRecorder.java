package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.domain.AuditLog;
import com.enterprise.iqk.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 动作审计记录器（聊天 + 工作流统一）：harness 每执行一个动作落一行 audit_log，
 * 与原 PlatformToolRuntime 的 tool_call 审计同格式（method=TOOL_CALL），覆盖
 * mcp/内建/平台/工作区全部动作与全部结局（成功/挂起待确认/错误/守卫拒绝）——
 * 此前聊天路径的 mcp_call 与内建工具只有内存轨迹与指标计数，事后无法回查
 * （2026-11-05 排查 DeepWiki 超时时实证）。失败只告警，绝不影响动作执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HarnessAuditRecorder {

    private final AuditLogMapper auditLogMapper;

    /**
     * 落一行动作审计。outcome 语义：观测载荷内的 status 优先（如 pending_confirmation），
     * 其余取观测状态（success/error），守卫拒绝为 policy_denied、无 runtime 为 no_runtime。
     * detail 为调用方整理的脱敏摘要（放行 = 脱敏输入 JSON，拒绝 = 拒绝原因）。
     */
    public void record(AgentAction action, String source, String outcome,
                       long latencyMs, String detail) {
        try {
            auditLogMapper.insert(AuditLog.builder()
                    .requestId(MDC.get("request_id"))
                    .traceId(MDC.get("trace_id"))
                    .tenantId(action.tenantId())
                    .userIdentity(action.chatId())
                    .method("TOOL_CALL")
                    .path("/agent-runtime/" + action.action() + "/" + actionTarget(action))
                    .statusCode("policy_denied".equals(outcome) ? 403
                            : ("success".equals(outcome) ? 200 : 500))
                    .durationMs(Math.max(0, latencyMs))
                    .chatId(action.chatId())
                    .extraPayload(buildExtra(action, source, outcome, detail))
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception ex) {
            log.warn("action 审计写入失败（不影响执行）: action={}, reason={}",
                    action.action(), ex.toString());
        }
    }

    /** 审计 path 的目标段：mcp 用 server/tool，其余有 tool 用 tool 名，都没有退回动作名。 */
    private String actionTarget(AgentAction action) {
        Map<String, Object> input = action.actionInput();
        Object tool = input.get("tool");
        Object server = input.get("server");
        if (tool instanceof String toolName && StringUtils.hasText(toolName)) {
            return server instanceof String serverName && StringUtils.hasText(serverName)
                    ? serverName + "/" + toolName : toolName;
        }
        return action.action();
    }

    /** 载荷：主体标识 + 动作来源与结局 + 调用方整理的脱敏摘要（截断，控审计行体积）。 */
    private String buildExtra(AgentAction action, String source, String outcome, String detail) {
        StringBuilder extra = new StringBuilder("agentId=%s,source=%s,outcome=%s".formatted(
                action.agentId(), source, outcome));
        if (StringUtils.hasText(detail)) {
            extra.append(";detail=").append(truncate(detail, 400));
        }
        return extra.toString();
    }

    private String truncate(String text, int maxChars) {
        String cleaned = text == null ? "" : text.trim();
        return cleaned.length() <= maxChars ? cleaned : cleaned.substring(0, maxChars) + "…";
    }
}
