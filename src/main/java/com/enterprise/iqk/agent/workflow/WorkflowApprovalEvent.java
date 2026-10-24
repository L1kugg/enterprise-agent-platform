package com.enterprise.iqk.agent.workflow;

/** 工作流人工审批结果事件：确认服务审批/取消后发布，图运行时监听以继续或终止任务（事件解耦避免循环依赖）。 */
public record WorkflowApprovalEvent(String taskId, String stepId, boolean approved) {
}
