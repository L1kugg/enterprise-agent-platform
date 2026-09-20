package com.enterprise.iqk.agent.harness;

import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 动作请求：ReAct 循环解析出的"模型想做什么"的完整描述。
 * 紧凑构造器统一做规范化（动作名小写、输入只读化、空白字段归一为空串），
 * 保证下游守卫与 runtime 拿到的永远是规整数据。
 */
public record AgentAction(
        /** 动作名；空白时默认 "finish"（视为模型主动结束） */
        String action,
        /** 动作输入（只读 Map，字段受 schema 约束） */
        Map<String, Object> actionInput,
        /** 触发本次动作的用户原始问题（供 rag_search 兜底当查询词） */
        String prompt,
        /** 租户 ID（数据隔离键） */
        String tenantId,
        /** 会话 ID */
        String chatId,
        /** 模型档位（透传给模型路由） */
        String modelProfile,
        /** 工作流任务 ID（有值时事件才落库留痕） */
        String taskId,
        /** 工作流步骤 ID */
        String stepId,
        /** 是否经受信运行时（token 确认流程）发起——只有受信动作需要 */
        boolean trustedRuntimeAccess
) {
    public AgentAction(String action,
                       Map<String, Object> actionInput,
                       String prompt,
                       String tenantId,
                       String chatId,
                       String modelProfile,
                       String taskId,
                       String stepId) {
        this(action, actionInput, prompt, tenantId, chatId, modelProfile, taskId, stepId, false);
    }

    /** 规范化：动作名小写去空白、输入包成只读 Map、空字段统一为空串 */
    public AgentAction {
        action = normalize(action);
        actionInput = actionInput == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(actionInput));
        prompt = emptyIfBlank(prompt);
        tenantId = emptyIfBlank(tenantId);
        chatId = emptyIfBlank(chatId);
        modelProfile = emptyIfBlank(modelProfile);
        taskId = emptyIfBlank(taskId);
        stepId = emptyIfBlank(stepId);
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "finish";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String emptyIfBlank(String value) {
        return StringUtils.hasText(value) ? value : "";
    }
}
