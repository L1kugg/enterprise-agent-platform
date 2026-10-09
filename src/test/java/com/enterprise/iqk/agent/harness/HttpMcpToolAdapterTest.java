package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class HttpMcpToolAdapterTest {

    @Test
    void callsConfiguredMcpHttpTool() throws Exception {
        TestServer testServer = startAdapter(mcp -> {}, exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String requestBody = new String(body, StandardCharsets.UTF_8);
            byte[] response = ("{\"ok\":true,\"request\":" + new ObjectMapper().writeValueAsString(requestBody)
                    + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        try {
            HttpMcpToolAdapter adapter = testServer.adapter();

            assertThat(adapter.supports("demo", "echo")).isTrue();
            Object result = adapter.execute("demo", "echo", Map.of("text", "hello"));

            assertThat(result).isInstanceOf(Map.class);
            assertThat(String.valueOf(((Map<?, ?>) result).get("request"))).contains("tools/call", "hello");
        } finally {
            testServer.stop();
        }
    }

    @Test
    void retriesTransientServerErrorThenSucceeds() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        TestServer testServer = startAdapter(mcp -> {}, echoHandler(exchange -> {
            if (requests.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, -1); // 首次回 503 模拟服务端瞬时抖动
                exchange.close();
                return;
            }
            respondOk(exchange);
        }));
        try {
            Object result = testServer.adapter().execute("demo", "echo", Map.of("text", "hello"));

            assertThat(requests.get()).isEqualTo(2); // 503 重试一次后成功
            assertThat(result).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) result).get("ok")).isEqualTo(true);
        } finally {
            testServer.stop();
        }
    }

    @Test
    void retriesNetworkFailureThenSucceeds() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        TestServer testServer = startAdapter(mcp -> {}, echoHandler(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (requests.incrementAndGet() == 1) {
                exchange.close(); // 不写响应直接断开连接，客户端收到 IO 异常
                return;
            }
            respondOk(exchange);
        }));
        try {
            Object result = testServer.adapter().execute("demo", "echo", Map.of("text", "hello"));

            assertThat(requests.get()).isEqualTo(2); // 网络异常重试一次后成功
            assertThat(result).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) result).get("ok")).isEqualTo(true);
        } finally {
            testServer.stop();
        }
    }

    @Test
    void returnsErrorAfterRetriesExhausted() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        TestServer testServer = startAdapter(mcp -> {}, echoHandler(exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1); // 持续 500，验证重试次数有上限
        }));
        try {
            Object result = testServer.adapter().execute("demo", "echo", Map.of("text", "hello"));

            assertThat(requests.get()).isEqualTo(3); // 默认 2 次重试：共 3 次请求
            assertThat(result).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) result).get("message")).isEqualTo("mcp http status: 500");
        } finally {
            testServer.stop();
        }
    }

    @Test
    void doesNotRetryClientError() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        TestServer testServer = startAdapter(mcp -> {}, echoHandler(exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1); // 4xx 是确定性错误，不应重试
        }));
        try {
            Object result = testServer.adapter().execute("demo", "echo", Map.of("text", "hello"));

            assertThat(requests.get()).isEqualTo(1);
            assertThat(((Map<?, ?>) result).get("message")).isEqualTo("mcp http status: 404");
        } finally {
            testServer.stop();
        }
    }

    @Test
    void zeroRetryAttemptsDisablesRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        TestServer testServer = startAdapter(mcp -> mcp.setRetryAttempts(0), echoHandler(exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
        }));
        try {
            Object result = testServer.adapter().execute("demo", "echo", Map.of("text", "hello"));

            assertThat(requests.get()).isEqualTo(1); // 重试关闭：只发一次
            assertThat(((Map<?, ?>) result).get("message")).isEqualTo("mcp http status: 500");
        } finally {
            testServer.stop();
        }
    }

    /** 只区分请求序号的回调；sendResponseHeaders 等操作会抛 IOException，故自建函数式接口而非 Consumer */
    @FunctionalInterface
    private interface EchoAttempt {
        void handle(HttpExchange exchange) throws java.io.IOException;
    }

    /** 统一吃掉请求体后回调按序号处理的 echo 处理器 */
    private static com.sun.net.httpserver.HttpHandler echoHandler(EchoAttempt byAttempt) {
        return exchange -> {
            exchange.getRequestBody().readAllBytes();
            byAttempt.handle(exchange);
        };
    }

    private static void respondOk(HttpExchange exchange) throws java.io.IOException {
        byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    /** 起本地 HttpServer 并搭好 demo/echo 的适配器；回环主机需白名单收窄 + 内部地址显式授权（默认 fail-closed） */
    private record TestServer(HttpServer server, HttpMcpToolAdapter adapter) {
        void stop() {
            server.stop(0);
        }
    }

    private static TestServer startAdapter(Consumer<AgentHarnessProperties.Mcp> mcpTuner,
                                           com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/mcp/tools/call", handler);
        server.start();
        AgentHarnessProperties properties = new AgentHarnessProperties();
        AgentHarnessProperties.McpServer mcpServer = new AgentHarnessProperties.McpServer();
        mcpServer.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        mcpServer.getTools().put("echo", new AgentHarnessProperties.McpTool());
        properties.getMcp().getServers().put("demo", mcpServer);
        properties.getMcp().setAllowedHosts(java.util.List.of("localhost", "127.0.0.1", "::1"));
        properties.getMcp().setAllowedInternalAddresses(
                java.util.List.of(grant("localhost", null, "127.0.0.1/32", "::1/128")));
        properties.getMcp().setRetryBackoffMs(1); // 测试不真等退避
        mcpTuner.accept(properties.getMcp());
        return new TestServer(server, new HttpMcpToolAdapter(properties, new ObjectMapper()));
    }

    // ── SSRF 校验核心直测（不再依赖起本地服务） ──────────────────────

    @Test
    void allowlistedHostWithoutGrantCannotReachLocalAddress() {
        // P0 回归：白名单命中但解析到回环地址且无显式授权 —— 旧实现命中白名单即放行
        AgentHarnessProperties.Mcp mcp = new AgentHarnessProperties.Mcp();
        mcp.setAllowedHosts(java.util.List.of("localhost"));
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://localhost:8080/mcp", mcp)).isFalse();
    }

    @Test
    void internalAddressAccessibleOnlyWithMatchingGrant() {
        AgentHarnessProperties.Mcp mcp = new AgentHarnessProperties.Mcp();
        mcp.setAllowedHosts(java.util.List.of("localhost"));
        mcp.setAllowedInternalAddresses(java.util.List.of(grant("localhost", null, "127.0.0.1/32", "::1/128")));
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://localhost:8080/mcp", mcp)).isTrue();

        // 授权声明了端口而实际端口不符：不放行
        mcp.setAllowedInternalAddresses(java.util.List.of(grant("localhost", 99, "127.0.0.1/32", "::1/128")));
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://localhost:8080/mcp", mcp)).isFalse();
    }

    @Test
    void grantsAreScopedToTheirDeclaredHost() throws Exception {
        // 授权只对声明的主机生效，不能被其它白名单主机借用
        assertThat(HttpMcpToolAdapter.addressesAuthorized("other-host", 8080,
                new java.net.InetAddress[]{java.net.InetAddress.getByName("127.0.0.1")},
                java.util.List.of(grant("localhost", null, "127.0.0.1/32")))).isFalse();
    }

    @Test
    void grantsCoverComposeServiceNetwork() throws Exception {
        // 模拟 compose 服务名解析到 Docker 默认容器网段（172.16.0.0/12）
        AgentHarnessProperties.AddressGrant g = grant("mcp-weather", 9101, "172.16.0.0/12");
        java.net.InetAddress inRange = java.net.InetAddress.getByName("172.18.0.5");
        // 192.168.x 仍是私有地址（需要授权）但在授权网段之外
        java.net.InetAddress outOfRange = java.net.InetAddress.getByName("192.168.1.5");

        assertThat(HttpMcpToolAdapter.addressesAuthorized("mcp-weather", 9101,
                new java.net.InetAddress[]{inRange}, java.util.List.of(g))).isTrue();
        // 端口/主机/网段任一不符即拒绝
        assertThat(HttpMcpToolAdapter.addressesAuthorized("mcp-weather", 9102,
                new java.net.InetAddress[]{inRange}, java.util.List.of(g))).isFalse();
        assertThat(HttpMcpToolAdapter.addressesAuthorized("other-host", 9101,
                new java.net.InetAddress[]{inRange}, java.util.List.of(g))).isFalse();
        assertThat(HttpMcpToolAdapter.addressesAuthorized("mcp-weather", 9101,
                new java.net.InetAddress[]{outOfRange}, java.util.List.of(g))).isFalse();
    }

    @Test
    void publicHostNeedsNoGrant() throws Exception {
        // 公共地址无需授权即可访问（原有默认行为保留）
        assertThat(HttpMcpToolAdapter.addressesAuthorized("example.com", 443,
                new java.net.InetAddress[]{java.net.InetAddress.getByName("8.8.8.8")},
                java.util.List.of())).isTrue();
    }

    @Test
    void rejectsUnreachableOrIllegalEndpoints() throws Exception {
        AgentHarnessProperties.Mcp mcp = new AgentHarnessProperties.Mcp();
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("ftp://example.com", mcp)).isFalse(); // 非 http(s) scheme
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http:///path", mcp)).isFalse(); // 空 host
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("", mcp)).isFalse(); // 空 baseUrl
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://nonexistent.invalid", mcp)).isFalse(); // 域名无法解析
        // 云元数据地址字面量（链路本地）无授权即拒绝
        assertThat(HttpMcpToolAdapter.isSafeBaseUrl("http://169.254.169.254/latest/meta-data/", mcp)).isFalse();
    }

    /** 授权项构造辅助 */
    private static AgentHarnessProperties.AddressGrant grant(String host, Integer port, String... cidrs) {
        AgentHarnessProperties.AddressGrant g = new AgentHarnessProperties.AddressGrant();
        g.setHost(host);
        g.setPort(port);
        g.setCidrs(java.util.List.of(cidrs));
        return g;
    }
}
