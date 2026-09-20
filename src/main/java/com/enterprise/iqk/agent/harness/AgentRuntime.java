package com.enterprise.iqk.agent.harness;

/**
 * 动作执行运行时接口：每种执行环境一个实现
 * （BuiltinToolRuntime 进程内工具 / McpToolRuntime 外部 MCP / WorkspaceRuntime 工作区）。
 * AgentHarnessService 按 supports() 顺序挑选第一个支持该动作的 runtime 分发。
 */
public interface AgentRuntime {
    /** 运行时标识（与 ActionSchema.runtime 对应） */
    String source();

    /** 是否支持给定动作 */
    boolean supports(String action);

    /** 执行动作，返回统一观测结构（实现不应抛异常，失败走 error 观测） */
    AgentObservation execute(AgentAction action);
}
