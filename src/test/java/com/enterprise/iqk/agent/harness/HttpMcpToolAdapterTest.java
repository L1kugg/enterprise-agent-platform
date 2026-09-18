package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpMcpToolAdapterTest {

    @Test
    void callsConfiguredMcpHttpTool() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/mcp/tools/call", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String requestBody = new String(body, StandardCharsets.UTF_8);
            byte[] response = ("{\"ok\":true,\"request\":" + new ObjectMapper().writeValueAsString(requestBody)
                    + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AgentHarnessProperties properties = new AgentHarnessProperties();
            AgentHarnessProperties.McpServer mcpServer = new AgentHarnessProperties.McpServer();
            mcpServer.setBaseUrl("http://localhost:" + server.getAddress().getPort());
            mcpServer.getTools().put("echo", new AgentHarnessProperties.McpTool());
            properties.getMcp().getServers().put("demo", mcpServer);
            // 环回主机（localhost / 127.0.0.1）默认会被 SSRF 防护拒绝；
            // 测试必须显式加入允许列表，以保证生产环境的
            // 防护保持 fail-closed（默认拒绝）。
            properties.getMcp().setAllowedHosts(java.util.List.of("localhost", "127.0.0.1", "::1"));
            HttpMcpToolAdapter adapter = new HttpMcpToolAdapter(properties, new ObjectMapper());

            Object result = adapter.execute("demo", "echo", Map.of("text", "hello"));

            assertThat(adapter.supports("demo", "echo")).isTrue();
            assertThat(result).isInstanceOf(Map.class);
            assertThat(String.valueOf(((Map<?, ?>) result).get("request"))).contains("tools/call", "hello");
        } finally {
            server.stop(0);
        }
    }
}
