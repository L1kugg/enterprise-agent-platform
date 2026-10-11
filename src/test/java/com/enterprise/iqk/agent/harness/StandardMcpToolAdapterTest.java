package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标准 MCP 协议适配器测试：对内建的最小合规 Streamable HTTP server（JDK HttpServer，
 * initialize 握手 / Mcp-Session-Id 会话 / tools/list / tools/call / 202 通知 / SSE 帧）
 * 重建原自研桥接的六类协议用例，并补会话失效重建与 SSE 帧解析两类新语义。
 * 回环主机走与生产同姿势的 SSRF 授权（白名单 + 显式内网授权，默认 fail-closed）。
 */
class StandardMcpToolAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestContext context;

    @AfterEach
    void stopServer() {
        if (context != null) {
            context.stop();
            context = null;
        }
    }

    @Test
    void executesStandardToolCallWithHandshakeAndSession() throws Exception {
        context = start(mcp -> {}, server -> {});

        Object result = context.adapter().execute("demo", "echo", Map.of("text", "hello"));

        assertThat(result).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result).get("ok")).isEqualTo(true);
        assertThat(((Map<?, ?>) result).get("echo")).isEqualTo("hello");
        assertThat(context.server().initializeCount.get()).isEqualTo(1); // 标准 initialize 握手恰好一次
        assertThat(context.server().initializedNotificationCount.get()).isEqualTo(1); // SDK 补发 notifications/initialized
        assertThat(context.server().lastToolCallSessionHeader).isEqualTo(context.server().issuedSessionId);

        // 会话复用：第二次调用不再重新握手
        context.adapter().execute("demo", "echo", Map.of("text", "again"));
        assertThat(context.server().initializeCount.get()).isEqualTo(1);
    }

    @Test
    void retriesTransientServerErrorThenSucceeds() throws Exception {
        context = start(mcp -> {}, server ->
                server.faultPlan = (method, attempt) -> "tools/call".equals(method) && attempt == 1 ? 503 : 0);

        Object result = context.adapter().execute("demo", "echo", Map.of("text", "hello"));

        assertThat(context.server().attemptCount("tools/call")).isEqualTo(2); // 503 重试一次后成功
        assertThat(((Map<?, ?>) result).get("ok")).isEqualTo(true);
    }

    @Test
    void retriesNetworkFailureThenSucceeds() throws Exception {
        context = start(mcp -> {}, server ->
                server.faultPlan = (method, attempt) -> "tools/call".equals(method) && attempt == 1 ? -1 : 0);

        Object result = context.adapter().execute("demo", "echo", Map.of("text", "hello"));

        assertThat(context.server().attemptCount("tools/call")).isEqualTo(2); // 网络异常重试一次后成功
        assertThat(((Map<?, ?>) result).get("ok")).isEqualTo(true);
    }

    @Test
    void returnsErrorAfterRetriesExhausted() throws Exception {
        context = start(mcp -> {}, server ->
                server.faultPlan = (method, attempt) -> "tools/call".equals(method) ? 500 : 0);

        Object result = context.adapter().execute("demo", "echo", Map.of("text", "hello"));

        assertThat(context.server().attemptCount("tools/call")).isEqualTo(3); // 默认 2 次重试：共 3 次请求
        assertThat(result).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result).get("status")).isEqualTo("error");
        assertThat(String.valueOf(((Map<?, ?>) result).get("message"))).contains("mcp call failed");
    }

    @Test
    void doesNotRetryClientError() throws Exception {
        context = start(mcp -> {}, server ->
                server.faultPlan = (method, attempt) -> "tools/call".equals(method) ? 404 : 0);

        Object result = context.adapter().execute("demo", "echo", Map.of("text", "hello"));

        assertThat(context.server().attemptCount("tools/call")).isEqualTo(1); // 4xx 是确定性错误，不重试
        assertThat(((Map<?, ?>) result).get("status")).isEqualTo("error");
    }

    @Test
    void zeroRetryAttemptsDisablesRetry() throws Exception {
        context = start(mcp -> mcp.setRetryAttempts(0), server ->
                server.faultPlan = (method, attempt) -> "tools/call".equals(method) ? 500 : 0);

        Object result = context.adapter().execute("demo", "echo", Map.of("text", "hello"));

        assertThat(context.server().attemptCount("tools/call")).isEqualTo(1); // 重试关闭：只发一次
        assertThat(((Map<?, ?>) result).get("status")).isEqualTo("error");
    }

    @Test
    void recoversAfterSessionInvalidation() throws Exception {
        context = start(mcp -> {}, server -> {});

        assertThat(((Map<?, ?>) context.adapter().execute("demo", "echo", Map.of("text", "one"))).get("ok"))
                .isEqualTo(true);
        assertThat(context.server().initializeCount.get()).isEqualTo(1);

        // 模拟会话过期：后续 tools/call 一律 404（Streamable HTTP 的会话失效语义）
        context.server().faultPlan = (method, attempt) -> "tools/call".equals(method) ? 404 : 0;
        Object failed = context.adapter().execute("demo", "echo", Map.of("text", "two"));
        assertThat(((Map<?, ?>) failed).get("status")).isEqualTo("error");

        // 恢复后：客户端已失效重建，重新握手成功
        context.server().faultPlan = (method, attempt) -> 0;
        Object recovered = context.adapter().execute("demo", "echo", Map.of("text", "three"));
        assertThat(((Map<?, ?>) recovered).get("ok")).isEqualTo(true);
        assertThat(context.server().initializeCount.get()).isEqualTo(2);
    }

    @Test
    void parsesSseFramedResponses() throws Exception {
        // SSE-only server：全部请求响应走 text/event-stream 帧（initialize 与 tools/call 都覆盖）
        context = start(mcp -> {}, server -> server.respondSse = true);

        Object result = context.adapter().execute("demo", "echo", Map.of("text", "framed"));

        assertThat(((Map<?, ?>) result).get("ok")).isEqualTo(true);
        assertThat(((Map<?, ?>) result).get("echo")).isEqualTo("framed");
        assertThat(context.server().initializeCount.get()).isEqualTo(1);
    }

    @Test
    void discoversToolsViaStandardListTools() throws Exception {
        context = start(mcp -> {}, server -> {});

        List<Map<String, Object>> tools = context.adapter().listTools("demo");

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).get("name")).isEqualTo("echo");
        assertThat(String.valueOf(tools.get(0).get("description"))).contains("回声");
        assertThat(tools.get(0).get("inputSchema")).isInstanceOf(Map.class);
        assertThat((Map<?, ?>) tools.get(0).get("inputSchema"))
                .satisfies(schema -> assertThat(String.valueOf(((Map<?, ?>) schema).get("required")))
                        .contains("text"));
        assertThat(context.server().attemptCount("tools/list")).isEqualTo(1);
    }

    // ── 测试基建：内建最小合规 Streamable HTTP MCP server ──────────────

    /** 故障注入：按 (method, 该方法第几次请求) 返回 0=健康 / 4xx5xx=状态码 / -1=断连。 */
    @FunctionalInterface
    private interface Fault {
        int faultFor(String method, int attempt);
    }

    private record TestContext(StandardMcpServer server, HttpServer httpServer, StandardMcpToolAdapter adapter) {
        void stop() {
            adapter.shutdown();
            httpServer.stop(0);
        }
    }

    private static TestContext start(Consumer<AgentHarnessProperties.Mcp> mcpTuner,
                                     Consumer<StandardMcpServer> serverTuner) throws Exception {
        StandardMcpServer mcpServer = new StandardMcpServer();
        serverTuner.accept(mcpServer);
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpServer.createContext("/mcp", mcpServer::handle);
        httpServer.start();

        AgentHarnessProperties properties = new AgentHarnessProperties();
        AgentHarnessProperties.McpServer serverConfig = new AgentHarnessProperties.McpServer();
        serverConfig.setBaseUrl("http://localhost:" + httpServer.getAddress().getPort());
        serverConfig.getTools().put("echo", new AgentHarnessProperties.McpTool());
        properties.getMcp().getServers().put("demo", serverConfig);
        properties.getMcp().setAllowedHosts(List.of("localhost", "127.0.0.1", "::1"));
        properties.getMcp().setAllowedInternalAddresses(
                List.of(grant("localhost", null, "127.0.0.1/32", "::1/128")));
        properties.getMcp().setRetryBackoffMs(1); // 测试不真等退避
        mcpTuner.accept(properties.getMcp());
        return new TestContext(mcpServer, httpServer, new StandardMcpToolAdapter(properties, MAPPER));
    }

    /** 最小合规 Streamable HTTP MCP server：initialize/通知/tools-list/tools-call，支持故障注入与 SSE 帧响应。 */
    static final class StandardMcpServer {
        volatile Fault faultPlan = (method, attempt) -> 0;
        volatile boolean respondSse = false;

        final AtomicInteger initializeCount = new AtomicInteger();
        final AtomicInteger initializedNotificationCount = new AtomicInteger();
        private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        volatile String issuedSessionId;
        volatile String lastToolCallSessionHeader;

        int attemptCount(String method) {
            return attempts.getOrDefault(method, new AtomicInteger()).get();
        }

        void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode request = MAPPER.readTree(body);
            String method = request.path("method").asText("");
            boolean notification = request.path("id").isMissingNode() || request.path("id").isNull();
            int attempt = attempts.computeIfAbsent(method, k -> new AtomicInteger()).incrementAndGet();

            if ("tools/call".equals(method)) {
                lastToolCallSessionHeader = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
            }

            int fault = faultPlan.faultFor(method, attempt);
            if (fault == -1) {
                exchange.close(); // 不写响应直接断连，客户端收到 IO 异常
                return;
            }
            if (fault > 0) {
                exchange.sendResponseHeaders(fault, -1);
                exchange.close();
                return;
            }
            if (notification) {
                if ("notifications/initialized".equals(method)) {
                    initializedNotificationCount.incrementAndGet();
                }
                exchange.sendResponseHeaders(202, -1); // 通知的合规响应：202 无体
                exchange.close();
                return;
            }

            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            response.set("result", resultFor(method, request));
            byte[] payload = respondSse
                    ? ("event: message\ndata: " + MAPPER.writeValueAsString(response) + "\n\n")
                            .getBytes(StandardCharsets.UTF_8)
                    : MAPPER.writeValueAsBytes(response);
            if ("initialize".equals(method)) {
                initializeCount.incrementAndGet();
                issuedSessionId = UUID.randomUUID().toString();
                exchange.getResponseHeaders().add("Mcp-Session-Id", issuedSessionId);
            }
            exchange.getResponseHeaders().add("Content-Type",
                    respondSse ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        }

        private ObjectNode resultFor(String method, JsonNode request) throws IOException {
            return switch (method) {
                case "initialize" -> {
                    // 回显客户端请求的协议版本（协商语义），并声明 tools 能力（SDK 侧会校验后才放行调用）
                    String version = request.path("params").path("protocolVersion").asText("2025-06-18");
                    ObjectNode result = MAPPER.createObjectNode();
                    result.put("protocolVersion", version);
                    ObjectNode capabilities = MAPPER.createObjectNode();
                    capabilities.set("tools", MAPPER.createObjectNode().put("listChanged", false));
                    result.set("capabilities", capabilities);
                    ObjectNode serverInfo = MAPPER.createObjectNode();
                    serverInfo.put("name", "test-mcp-server");
                    serverInfo.put("version", "1.0.0");
                    result.set("serverInfo", serverInfo);
                    yield result;
                }
                case "tools/list" -> {
                    ObjectNode result = MAPPER.createObjectNode();
                    ObjectNode tool = MAPPER.createObjectNode();
                    tool.put("name", "echo");
                    tool.put("description", "回声工具：原样返回 text 参数");
                    ObjectNode schema = MAPPER.createObjectNode();
                    schema.put("type", "object");
                    ObjectNode properties = MAPPER.createObjectNode();
                    ObjectNode textProp = MAPPER.createObjectNode();
                    textProp.put("type", "string");
                    textProp.put("description", "要回声的文本");
                    properties.set("text", textProp);
                    schema.set("properties", properties);
                    schema.set("required", MAPPER.createArrayNode().add("text"));
                    tool.set("inputSchema", schema);
                    result.set("tools", MAPPER.createArrayNode().add(tool));
                    yield result;
                }
                case "tools/call" -> {
                    ObjectNode result = MAPPER.createObjectNode();
                    String text = request.path("params").path("arguments").path("text").asText("");
                    // 文本内容本身是 JSON 字符串：覆盖适配器"可解析则还原结构"的结果转换
                    ObjectNode echoed = MAPPER.createObjectNode();
                    echoed.put("ok", true);
                    echoed.put("echo", text);
                    result.set("content", MAPPER.createArrayNode().add(
                            MAPPER.createObjectNode().put("type", "text")
                                    .put("text", MAPPER.writeValueAsString(echoed))));
                    result.put("isError", false);
                    yield result;
                }
                default -> MAPPER.createObjectNode();
            };
        }
    }

    private static AgentHarnessProperties.AddressGrant grant(String host, Integer port, String... cidrs) {
        AgentHarnessProperties.AddressGrant g = new AgentHarnessProperties.AddressGrant();
        g.setHost(host);
        g.setPort(port);
        g.setCidrs(List.of(cidrs));
        return g;
    }
}
