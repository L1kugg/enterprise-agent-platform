package com.enterprise.iqk.agent.harness;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MCP 工具运行时：处理 mcp_call 动作，把调用转发给注册的 McpToolAdapter。
 * runtime 层只做"选适配器 + 统一观测"，协议细节（JSON-RPC、SSRF 防护、响应上限）
 * 全部下沉到适配器实现（如 HttpMcpToolAdapter）。
 */
@Component
@RequiredArgsConstructor
public class McpToolRuntime implements AgentRuntime {
    /** 全部 MCP 适配器，按 server/tool 精确匹配取第一个 */
    private final List<McpToolAdapter> adapters;

    @Override
    public String source() {
        return "mcp";
    }

    @Override
    public boolean supports(String action) {
        return "mcp_call".equals(action);
    }

    /** 从动作输入取 server/tool/arguments，找不到支持该组合的适配器即报未注册 */
    @Override
    public AgentObservation execute(AgentAction action) {
        long startedNs = System.nanoTime();
        String server = stringVal(action.actionInput(), "server");
        String tool = stringVal(action.actionInput(), "tool");
        Map<String, Object> arguments = mapVal(action.actionInput().get("arguments"));
        McpToolAdapter adapter = adapters.stream()
                .filter(candidate -> candidate.supports(server, tool))
                .findFirst()
                .orElse(null);
        if (adapter == null) {
            return AgentObservation.error(source(),
                    "mcp tool is not registered: " + server + "/" + tool,
                    elapsedMs(startedNs));
        }
        Object result = adapter.execute(server, tool, arguments);
        if (result instanceof Map<?, ?> resultMap && "error".equals(resultMap.get("status"))) {
            Object message = resultMap.get("message");
            return AgentObservation.error(source(),
                    message == null ? "mcp tool failed" : String.valueOf(message),
                    elapsedMs(startedNs));
        }
        return AgentObservation.success(source(), Map.of(
                "server", server,
                "tool", tool,
                "result", result
        ), elapsedMs(startedNs));
    }

    /** 非 Map 输入统一降级为空 Map（arguments 缺省不发参数） */
    private Map<String, Object> mapVal(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    /** 取字符串字段：null 返回空串 */
    private String stringVal(Map<String, Object> input, String key) {
        Object raw = input.get(key);
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private long elapsedMs(long startedNs) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
    }
}
