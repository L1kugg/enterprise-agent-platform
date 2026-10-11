package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 标准 MCP 协议工具适配器：按 app.agent-harness.mcp 配置为每个 server 维护一个
 * 懒初始化的官方 SDK {@link McpSyncClient}（传输层为自研 HC5 实现，见
 * {@link Hc5StreamableHttpTransport}），调用走标准 initialize 握手 + tools/list、
 * tools/call。server/tool 的启用状态、baseUrl、endpoint 与超时仍全部来自配置，
 * supports() 五重校验（含 SSRF 预检）通过才放行调用。
 *
 * <p>会话生命周期：懒建 + 失败即弃。首次调用完成 initialize 握手后复用会话；
 * 任何调用失败都关闭并清空该 server 的客户端，下次调用重新握手重建——
 * 兼容 server 重启与会话过期，不需要后台保活。
 *
 * <p>结果语义与原自研桥接对齐：工具级失败（{@code isError=true}）与任何异常
 * 一律转 {@code status=error} 的 Map，不向 harness 抛异常，由 McpToolRuntime
 * 统一转 error 观测。
 */
@Slf4j
@Component
public class StandardMcpToolAdapter implements McpToolAdapter {

    private static final String CLIENT_NAME = "enterprise-agent-platform";
    private static final String CLIENT_VERSION = "1.0.0";
    /** tools/list 翻页上限：防御性上限，正常 server 一页就够。 */
    private static final int MAX_LIST_PAGES = 10;

    private final AgentHarnessProperties harnessProperties;
    private final ObjectMapper objectMapper;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    public StandardMcpToolAdapter(AgentHarnessProperties harnessProperties, ObjectMapper objectMapper) {
        this.harnessProperties = harnessProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String server() {
        return "configured-standard";
    }

    @Override
    public String tool() {
        return "configured-standard";
    }

    /** 五重校验：配置存在、server 启用、baseUrl 非空且安全（SSRF 预检）、tool 配置存在、tool 启用 */
    @Override
    public boolean supports(String server, String tool) {
        AgentHarnessProperties.McpServer serverConfig = harnessProperties.getMcp().getServers().get(server);
        return serverConfig != null
                && serverConfig.isEnabled()
                && StringUtils.hasText(serverConfig.getBaseUrl())
                && McpSsrfGuard.isSafeBaseUrl(serverConfig.getBaseUrl(), harnessProperties.getMcp())
                && serverConfig.getTools().containsKey(tool)
                && serverConfig.getTools().get(tool).isEnabled();
    }

    /** 标准 tools/call：懒建客户端（含 initialize 握手）→ 调用 → 结果转观测 Map；失败关客户端待重建，不抛异常。 */
    @Override
    @SuppressWarnings("PMD.CloseResource") // 客户端按 server 缓存复用，由 shutdown()/invalidate() 关闭
    public Object execute(String server, String tool, Map<String, Object> arguments) {
        if (!supports(server, tool)) {
            return Map.of("status", "error", "message", "mcp tool is not registered: " + server + "/" + tool);
        }
        McpSyncClient client;
        try {
            client = clientFor(server);
        } catch (Exception ex) {
            return Map.of("status", "error", "message", "mcp initialize failed: " + rootMessage(ex));
        }
        try {
            McpSchema.CallToolResult result =
                    client.callTool(new McpSchema.CallToolRequest(tool, arguments == null ? Map.of() : arguments));
            return toObservation(result);
        } catch (Exception ex) {
            invalidate(server);
            log.warn("MCP tools/call 失败（客户端已重置待重建）: server={}, tool={}, reason={}",
                    server, tool, ex.getMessage());
            return Map.of("status", "error", "message", "mcp call failed: " + rootMessage(ex));
        }
    }

    /** 不带 server/tool 的调用直接拒绝（本适配器必须显式指定目标）。 */
    @Override
    public Object execute(Map<String, Object> arguments) {
        return Map.of("status", "error", "message", "configured MCP call requires server and tool");
    }

    /**
     * 动态发现指定 server 的工具列表（标准 tools/list，跟随 nextCursor 翻页）。
     * 返回格式：[{"name":"...","description":"...","inputSchema":{...}}]。
     * server 不存在或上游不可达时返回空列表（启动阶段不因单个 server 故障阻断）。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource") // 客户端按 server 缓存复用，由 shutdown()/invalidate() 关闭
    public List<Map<String, Object>> listTools(String server) {
        AgentHarnessProperties.McpServer serverConfig = harnessProperties.getMcp().getServers().get(server);
        if (serverConfig == null || !serverConfig.isEnabled() || !StringUtils.hasText(serverConfig.getBaseUrl())) {
            return List.of();
        }
        try {
            McpSyncClient client = clientFor(server);
            List<Map<String, Object>> discovered = new ArrayList<>();
            String cursor = null;
            for (int page = 0; page < MAX_LIST_PAGES; page++) {
                McpSchema.ListToolsResult result = client.listTools(cursor);
                if (result != null && result.tools() != null) {
                    result.tools().forEach(t -> discovered.add(toDiscoveredTool(t)));
                }
                cursor = result == null ? null : result.nextCursor();
                if (!StringUtils.hasText(cursor)) {
                    return discovered;
                }
            }
            log.warn("MCP tools/list 翻页超过 {} 页，截断: server={}", MAX_LIST_PAGES, server);
            return discovered;
        } catch (Exception ex) {
            invalidate(server);
            log.warn("MCP tools/list 失败（客户端已重置待重建）: server={}, reason={}", server, ex.getMessage());
            return List.of();
        }
    }

    @PreDestroy
    void shutdown() {
        clients.forEach((server, client) -> {
            try {
                client.close();
            } catch (Exception ex) {
                log.warn("MCP 客户端关闭失败: server={}, reason={}", server, ex.getMessage());
            }
        });
        clients.clear();
    }

    /** 懒建 + 握手：并发首调经 compute 阻塞串行化；失败清空占位，下次重试。 */
    private McpSyncClient clientFor(String server) {
        return clients.compute(server, (name, existing) -> {
            if (existing != null) {
                return existing;
            }
            AgentHarnessProperties.McpServer serverConfig = harnessProperties.getMcp().getServers().get(name);
            McpSyncClient created = buildClient(serverConfig);
            created.initialize(); // 标准 initialize 握手（含 notifications/initialized 由 SDK 发出）
            return created;
        });
    }

    /** 组装一个 server 的客户端：HC5 自定义传输 + 官方同步客户端（请求超时取配置最大值加缓冲兜底）。 */
    private McpSyncClient buildClient(AgentHarnessProperties.McpServer serverConfig) {
        Map<String, Integer> toolTimeouts = new HashMap<>();
        int maxTimeoutMs = 5_000;
        for (Map.Entry<String, AgentHarnessProperties.McpTool> entry : serverConfig.getTools().entrySet()) {
            if (entry.getValue() != null && entry.getValue().getTimeoutMs() > 0) {
                toolTimeouts.put(entry.getKey(), entry.getValue().getTimeoutMs());
                maxTimeoutMs = Math.max(maxTimeoutMs, entry.getValue().getTimeoutMs());
            }
        }
        Hc5StreamableHttpTransport transport = new Hc5StreamableHttpTransport(
                serverConfig.getBaseUrl(),
                StringUtils.hasText(serverConfig.getEndpoint()) ? serverConfig.getEndpoint() : "/mcp",
                harnessProperties,
                objectMapper,
                5_000,
                toolTimeouts);
        return McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation(CLIENT_NAME, CLIENT_VERSION))
                .capabilities(new McpSchema.ClientCapabilities(null, null, null, null))
                // pending 请求的客户端侧兜底：必须大于传输层最长 HTTP 超时，真实超时由传输层按消息控制
                .requestTimeout(Duration.ofMillis(maxTimeoutMs + 5_000L))
                .build();
    }

    /** 失败即弃：关闭并移除该 server 的客户端，下次调用重新握手。 */
    @SuppressWarnings("PMD.CloseResource") // 下方 try 内显式 close，PMD 对 remove() 返回值追踪不到
    private void invalidate(String server) {
        McpSyncClient stale = clients.remove(server);
        if (stale != null) {
            try {
                stale.close();
            } catch (Exception ex) {
                log.debug("MCP 失效客户端关闭失败（忽略）: server={}, reason={}", server, ex.getMessage());
            }
        }
    }

    /** CallToolResult → 观测：isError 走 status=error；优先 structuredContent，其次文本内容（可解析 JSON 则还原结构）。 */
    private Object toObservation(McpSchema.CallToolResult result) {
        if (result == null) {
            return Map.of("status", "error", "message", "mcp call returned empty result");
        }
        if (Boolean.TRUE.equals(result.isError())) {
            return Map.of("status", "error", "message", textOf(result.content()));
        }
        Object structured = result.structuredContent();
        if (structured instanceof Map<?, ?> || structured instanceof List<?>) {
            return structured;
        }
        String text = textOf(result.content());
        if (!text.isBlank()) {
            try {
                return objectMapper.readValue(text, Object.class); // 文本内容若本身是 JSON，还原为结构
            } catch (IOException ignored) {
                return Map.of("content", text); // 纯文本按内容承载
            }
        }
        return Map.of();
    }

    /** 拼接文本类 content（多段以换行分隔）；非文本段序列化为 JSON 兜底。 */
    private String textOf(List<McpSchema.Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : contents) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            if (content instanceof McpSchema.TextContent textContent) {
                sb.append(textContent.text());
            } else {
                try {
                    sb.append(objectMapper.writeValueAsString(content));
                } catch (IOException ex) {
                    sb.append(String.valueOf(content));
                }
            }
        }
        return sb.toString();
    }

    /** SDK Tool → 目录 Map（name/description/inputSchema 三字段，供 McpToolCatalog 消费）。 */
    private Map<String, Object> toDiscoveredTool(McpSchema.Tool tool) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", tool.name());
        item.put("description", tool.description());
        item.put("inputSchema", tool.inputSchema() == null ? Map.of()
                : objectMapper.convertValue(tool.inputSchema(), Object.class));
        return item;
    }

    /** 异常链收敛为一条可展示 message。 */
    private static String rootMessage(Throwable ex) {
        String message = ex.getMessage();
        return StringUtils.hasText(message) ? message : ex.getClass().getSimpleName();
    }
}
