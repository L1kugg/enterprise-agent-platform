package com.enterprise.iqk.agent.harness;

/**
 * 策略判定结果：允许（携带命中的 schema）或拒绝（携带错误码与人读信息）。
 * 错误码枚举：missing_action / unsupported_action / disabled_action /
 * tenant_action_denied / trusted_runtime_required / trusted_runtime_disabled / invalid_action_input。
 */
public record ActionPolicyDecision(
        /** 是否放行 */
        boolean allowed,
        /** 拒绝时的错误码（允许时为 "allowed"） */
        String code,
        /** 人读说明（拒绝原因） */
        String message,
        /** 放行时命中的动作 schema；拒绝时为 null */
        ActionSchema schema
) {
    /** 放行工厂 */
    public static ActionPolicyDecision allow(ActionSchema schema) {
        return new ActionPolicyDecision(true, "allowed", "", schema);
    }

    /** 拒绝工厂 */
    public static ActionPolicyDecision deny(String code, String message) {
        return new ActionPolicyDecision(false, code, message, null);
    }
}
