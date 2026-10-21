package com.enterprise.iqk.service.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HEADER 位置参数（FR-R7-01 表单承诺的行为）：定义在 params 里 location=HEADER 的参数
 * 必须真实写进请求头（此前为静默丢弃的空实现）；自定义 Content-Type 可覆盖默认值。
 */
class ToolInvocationHeaderParamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer server;
    private static String base;
    /** 回显收到的请求头。 */
    private static volatile String seenTraceHeader = "";
    private static volatile String seenContentType = "";

    private final ToolInvocationService service = new ToolInvocationService(
            null, new CodeToolSandboxService(MAPPER), MAPPER,
            new com.enterprise.iqk.security.SecretCipher("", MAPPER));

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            seenTraceHeader = exchange.getRequestHeaders().getFirst("X-Trace-Id");
            seenContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void headerLocationParamsAreAppliedToRequest() throws Exception {
        JsonNode config = MAPPER.readTree("""
                {"toolType":"FORM_API","method":"POST","url":"%s/echo",
                 "params":[
                   {"name":"X-Trace-Id","location":"HEADER","required":true},
                   {"name":"memo","location":"BODY","required":true}]}""".formatted(base));

        Map<String, Object> result = service.invoke(config, Map.of("X-Trace-Id", "abc-123", "memo", "你好"));

        assertThat(result.get("status")).isEqualTo(200);
        assertThat(seenTraceHeader).isEqualTo("abc-123");
    }

    @Test
    void customContentTypeHeaderOverridesDefault() throws Exception {
        JsonNode config = MAPPER.readTree("""
                {"toolType":"FORM_API","method":"POST","url":"%s/echo",
                 "params":[
                   {"name":"Content-Type","location":"HEADER"},
                   {"name":"payload","location":"BODY","required":true}]}""".formatted(base));

        Map<String, Object> result = service.invoke(config,
                Map.of("Content-Type", "text/plain", "payload", "raw-body"));

        assertThat(result.get("status")).isEqualTo(200);
        assertThat(seenContentType).isEqualTo("text/plain");
    }
}
