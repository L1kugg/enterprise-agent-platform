package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 受信动作服务：trustedOnly 高危动作（workspace 写入、shell、mcp_call 等）
 * 的"预览 → 确认 → 执行"两段式流程。
 * preview 生成一次性 token 交由 TrustedActionStore 挂起（默认进程内存，
 * 多实例部署配置 app.agent-harness.trusted-action.store=redis），
 * execute 凭 token 原子消费后才放行执行——防"模型自己点头"执行高危操作。
 * 存储键以租户隔离：跨租户拿到的 token 在存储层天然不可见，不会被消费。
 */
@Service
@RequiredArgsConstructor
public class TrustedActionService {

    private final AgentHarnessService harnessService;
    private final ActionSchemaRegistry schemaRegistry;
    private final HarnessPayloadSanitizer payloadSanitizer;
    private final TrustedActionStore trustedActionStore;
    private final AgentHarnessProperties harnessProperties;

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
        Instant expiresAt = Instant.now().plus(harnessProperties.getTrustedAction().getTokenTtl());
        trustedActionStore.save(action.tenantId(), token,
                new PendingTrustedAction(action, expiresAt),
                harnessProperties.getTrustedAction().getTokenTtl());
        return new TrustedActionPreviewResponse(
                1,
                token,
                action.action(),
                expiresAt,
                previewPayload(action, schema)
        );
    }

    /**
     * 第二段：凭 token 执行。token 存在且未过期即由存储原子消费——
     * 同一 token 只能成功执行一次；租户隔离由存储键保证（跨租户 token 不可见）。
     */
    public AgentObservation execute(String token, String tenantId) {
        return trustedActionStore.consume(tenantId, token)
                .<AgentObservation>map(pending -> harnessService.execute(pending.action()))
                .orElseGet(() -> AgentObservation.error("trusted-action", "trusted action token not found", 0));
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
}
