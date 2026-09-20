package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动作策略守卫：所有动作执行前的统一准入闸门。
 * 依次过六道检验（顺序即优先级）：
 * ① missing_action —— 动作为空
 * ② unsupported_action —— 未在 schema 注册表注册
 * ③ disabled_action —— 被全局 disabledActions 熔断
 * ④ tenant_action_denied —— 租户配置了动作白名单且不含此动作
 * ⑤ trusted_runtime_required / trusted_runtime_disabled —— trustedOnly 动作必须来自受信流程且功能开启
 * ⑥ invalid_action_input —— 必填字段缺失、patch 动作缺 content/patch、出现 schema 外的未知字段
 * 任一道不通过即拒绝；全部通过才返回 allow（附命中 schema）。
 */
@Component
@RequiredArgsConstructor
public class ActionPolicyGuard {
    private final ActionSchemaRegistry schemaRegistry;
    private final AgentHarnessProperties harnessProperties;

    /** 简化判定：只看是否放行 */
    public boolean isAllowed(AgentAction action) {
        return evaluate(action).allowed();
    }

    /** 完整判定：返回放行/拒绝及原因，拒绝时携带错误码供观测回填 */
    public ActionPolicyDecision evaluate(AgentAction action) {
        if (action == null) {
            return ActionPolicyDecision.deny("missing_action", "action is required");
        }
        ActionSchema schema = schemaRegistry.find(action.action()).orElse(null);
        if (schema == null) {
            return ActionPolicyDecision.deny("unsupported_action", "unsupported action: " + action.action());
        }
        if (harnessProperties.getDisabledActions().contains(action.action())) {
            return ActionPolicyDecision.deny("disabled_action", "action is disabled: " + action.action());
        }
        List<String> tenantAllowed = tenantAllowedActions(action.tenantId());
        if (!tenantAllowed.isEmpty() && !tenantAllowed.contains(action.action())) {
            return ActionPolicyDecision.deny("tenant_action_denied",
                    "action is not allowed for tenant: " + action.action());
        }
        if (schema.trustedOnly() && !action.trustedRuntimeAccess()) {
            return ActionPolicyDecision.deny("trusted_runtime_required",
                    "action requires trusted runtime access: " + action.action());
        }
        if (schema.trustedOnly() && !harnessProperties.isTrustedRuntimeEnabled()) {
            return ActionPolicyDecision.deny("trusted_runtime_disabled",
                    "trusted runtime is disabled");
        }
        List<String> missing = missingRequiredFields(schema, action.actionInput());
        if (!missing.isEmpty()) {
            return ActionPolicyDecision.deny("invalid_action_input",
                    "missing required field(s): " + String.join(", ", missing));
        }
        if (requiresPatchPayload(action.action()) && !hasValue(action.actionInput().get("content"))
                && !hasValue(action.actionInput().get("patch"))) {
            return ActionPolicyDecision.deny("invalid_action_input",
                    "missing required field: content or patch");
        }
        List<String> unknown = unknownFields(schema, action.actionInput());
        if (!unknown.isEmpty()) {
            return ActionPolicyDecision.deny("invalid_action_input",
                    "unknown field(s): " + String.join(", ", unknown));
        }
        return ActionPolicyDecision.allow(schema);
    }

    /** 找出输入中缺失（null 或空白串）的必填字段 */
    private List<String> missingRequiredFields(ActionSchema schema, Map<String, Object> input) {
        List<String> missing = new ArrayList<>();
        for (String field : schema.requiredFields()) {
            if (!hasValue(input.get(field))) {
                missing.add(field);
            }
        }
        return missing;
    }

    /** 找出 schema 未声明的未知字段——防模型凭空捏造参数注入下游 */
    private List<String> unknownFields(ActionSchema schema, Map<String, Object> input) {
        List<String> unknown = new ArrayList<>();
        for (String field : input.keySet()) {
            if (!schema.knowsField(field)) {
                unknown.add(field);
            }
        }
        return unknown;
    }

    /** 字段是否有值：非 null 且（字符串时）非空白 */
    private boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return StringUtils.hasText(text);
        }
        return true;
    }

    /** 租户动作白名单：未配置（空）=不限制，配置了 = 仅允许列表内动作 */
    private List<String> tenantAllowedActions(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            return List.of();
        }
        return new ArrayList<>(harnessProperties.getTenantAllowedActions().getOrDefault(tenantId, Set.of()));
    }

    /** 补丁类动作（propose/apply）必须携带 content 或 patch 载荷之一 */
    private boolean requiresPatchPayload(String action) {
        return "workspace_propose_patch".equals(action) || "workspace_apply_patch".equals(action);
    }
}
