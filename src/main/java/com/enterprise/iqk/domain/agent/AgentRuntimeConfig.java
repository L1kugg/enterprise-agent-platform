package com.enterprise.iqk.domain.agent;

import java.util.List;

/**
 * 智能体运行时配置：绑定关系全部来自 agent_*_binding 关系表，
 * 标量配置（提示词/参数/开场白等）来自 config_json，由 AgentRuntimeConfigService 装配。
 */
public record AgentRuntimeConfig(
        Long agentId,
        String agentName,
        String status,
        String prompt,
        /** null = 未配置，沿用模型服务默认参数 */
        Double temperature,
        int memoryTurns,
        String openingMessage,
        List<String> openingQuestions,
        boolean executionConfirm,
        Long modelServiceId,
        /** 绑定模型服务配置里的模型名（如 qwen-plus） */
        String boundModelName,
        List<Long> safetyGuardIds,
        List<Long> workflowIds,
        List<Long> toolIds,
        List<Long> knowledgeBaseIds,
        List<String> publishChannels
) {

    /** 是否绑定了模型服务（运行时据此决定走绑定模型还是档位路由）。 */
    public boolean hasModel() {
        return modelServiceId != null;
    }
}
