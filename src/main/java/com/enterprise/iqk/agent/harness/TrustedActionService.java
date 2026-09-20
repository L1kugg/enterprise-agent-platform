package com.enterprise.iqk.agent.harness;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 受信动作服务：trustedOnly 高危动作（workspace 写入、shell、mcp_call 等）
 * 的"预览 → 确认 → 执行"两段式流程。
 * preview 生成一次性 token（10 分钟 TTL，内存 ConcurrentHashMap 挂起），
 * execute 凭 token 原子消费后才放行执行——防"模型自己点头"执行高危操作。
 */
@Service
@RequiredArgsConstructor
public class TrustedActionService {
    /** 确认 token 有效期：预览到确认之间留给人工/上游审阅的窗口 */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

    private final AgentHarnessService harnessService;
    private final ActionSchemaRegistry schemaRegistry;
    private final HarnessPayloadSanitizer payloadSanitizer;
    /** 挂起中的受信动作：token → 待执行动作（进程内存态，重启即失效，需重新预览） */
    private final Map<String, PendingTrustedAction> pendingActions = new ConcurrentHashMap<>();

    /**
     * 第一段：受理预览请求。校验动作存在且确为 trustedOnly，
     * 生成 token 存入挂起表并返回脱敏预览（apply_patch 类会试跑 propose 出 diff）。
     */
    public TrustedActionPreviewResponse preview(TrustedActionRequest request) {
        AgentAction action = toTrustedAction(request);
        ActionSchema schema = schemaRegistry.find(action.action())
                .orElseThrow(() -> new IllegalArgumentException("unsupported action: " + action.action()));
        if (!schema.trustedOnly()) {
            throw new IllegalArgumentException("action does not require trusted runtime: " + action.action());
        }

        String token = "ta-" + UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = Instant.now().plus(TOKEN_TTL);
        // 清理已过 TTL 但始终未被消费执行的 token；否则挂起映射表
        // 会随进程生命周期不断增长。
        pendingActions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        pendingActions.put(token, new PendingTrustedAction(action, expiresAt));
        return new TrustedActionPreviewResponse(
                1,
                token,
                action.action(),
                expiresAt,
                previewPayload(action, schema)
        );
    }

    /**
     * 第二段：凭 token 执行。三重校验缺一不可——
     * token 存在、租户匹配（防跨租户盗用）、未过期；
     * remove(token, pending) 原子消费保证同一 token 只能执行一次。
     */
    public AgentObservation execute(String token, String tenantId) {
        // 这里也顺带清理过期 token：目前只有 preview 路径会清扫该映射表，
        // 若运维只调用 execute（例如自动化确认循环），过期 token
        // 会一直堆积到下一次 preview 才被清除。
        pendingActions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        PendingTrustedAction pending = pendingActions.get(token);
        if (pending == null || !pending.action().tenantId().equals(tenantId)) {
            return AgentObservation.error("trusted-action", "trusted action token not found", 0);
        }
        if (!pendingActions.remove(token, pending)) {
            return AgentObservation.error("trusted-action", "trusted action token not found", 0);
        }
        if (pending.expiresAt().isBefore(Instant.now())) {
            return AgentObservation.error("trusted-action", "trusted action token expired", 0);
        }
        return harnessService.execute(pending.action());
    }

    /** 组装预览载荷：apply_patch 动作转成 propose_patch 真实试跑出 diff；其余动作给脱敏后的待确认输入 */
    private Map<String, Object> previewPayload(AgentAction action, ActionSchema schema) {
        if ("workspace_apply_patch".equals(action.action())) {
            AgentObservation observation = harnessService.execute(new AgentAction(
                    "workspace_propose_patch",
                    action.actionInput(),
                    action.prompt(),
                    action.tenantId(),
                    action.chatId(),
                    action.modelProfile(),
                    action.taskId(),
                    action.stepId(),
                    true
            ));
            return observation.toMap();
        }
        return Map.of(
                "status", "pending_confirmation",
                "source", schema.runtime(),
                "action", action.action(),
                "actionInput", payloadSanitizer.sanitizeActionInput(action, schema)
        );
    }

    /** 请求 → 动作对象：动作名必填；trustedRuntimeAccess 固定置 true（本服务就是受信入口） */
    private AgentAction toTrustedAction(TrustedActionRequest request) {
        if (request == null || !StringUtils.hasText(request.action())) {
            throw new IllegalArgumentException("action is required");
        }
        return new AgentAction(
                request.action(),
                request.actionInput(),
                request.prompt(),
                request.tenantId(),
                request.chatId(),
                request.modelProfile(),
                request.taskId(),
                request.stepId(),
                true
        );
    }

    /** 挂起项：待执行动作 + 过期时间 */
    private record PendingTrustedAction(AgentAction action, Instant expiresAt) {
    }
}
