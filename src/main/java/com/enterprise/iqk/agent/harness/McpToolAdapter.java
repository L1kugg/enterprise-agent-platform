package com.enterprise.iqk.agent.harness;

import java.util.Map;

/** MCP 工具适配器：绑定一对 server/tool，runtime 据此路由调用。每个外部工具一个实现。 */
public interface McpToolAdapter {
    /** 目标 MCP server 名 */
    String server();

    /** 目标工具名 */
    String tool();

    /** 是否支持给定 server/tool 组合（默认精确匹配自身绑定） */
    default boolean supports(String server, String tool) {
        return server().equals(server) && tool().equals(tool);
    }

    /** 带上下文执行（默认忽略 server/tool 直接转发） */
    default Object execute(String server, String tool, Map<String, Object> arguments) {
        return execute(arguments);
    }

    /** 执行工具调用，返回结构化结果（失败用 status=error 的 Map 表达） */
    Object execute(Map<String, Object> arguments);
}
