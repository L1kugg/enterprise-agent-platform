package com.enterprise.iqk.agent.harness;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 受信动作请求：高危动作（trustedOnly）先出预览、换取 token，确认后才真正执行。 */
public record TrustedActionRequest(
        /** 动作名（必须是 schema 标记 trustedOnly 的动作） */
        String action,
        /** 动作输入（只读 Map） */
        Map<String, Object> actionInput,
        /** 触发动作的用户问题 */
        String prompt,
        /** 租户 ID（会被服务端用认证身份覆盖，防止伪造） */
        String tenantId,
        /** 会话 ID */
        String chatId,
        /** 模型档位 */
        String modelProfile,
        /** 工作流任务 ID */
        String taskId,
        /** 工作流步骤 ID */
        String stepId
) {
    public TrustedActionRequest {
        actionInput = actionInput == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(actionInput));
    }

    /** 用认证后的真实租户 ID 覆盖请求里的租户字段——客户端声明不可信 */
    public TrustedActionRequest withTenantId(String authenticatedTenantId) {
        return new TrustedActionRequest(
                action, actionInput, prompt, authenticatedTenantId,
                chatId, modelProfile, taskId, stepId
        );
    }
}
