package com.enterprise.iqk.domain.agent;

/**
 * 智能体绑定表白名单：Mapper 里 ${table} 占位符只能取自这里，
 * 绝不接受外部输入，规避动态表名注入。
 */
public enum AgentBindingTable {
    MODEL("agent_model_binding", "model-services"),
    WORKFLOW("agent_workflow_binding", "workflows"),
    TOOL("agent_tool_binding", "tools"),
    KNOWLEDGE("agent_knowledge_binding", "knowledge-bases"),
    SAFETY("agent_safety_binding", "safety-guards"),
    PUBLISH_CHANNEL("agent_publish_channel", "publish-channel");

    private final String tableName;
    private final String resourceType;

    AgentBindingTable(String tableName, String resourceType) {
        this.tableName = tableName;
        this.resourceType = resourceType;
    }

    public String tableName() {
        return tableName;
    }

    public String resourceType() {
        return resourceType;
    }
}
