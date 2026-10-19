package com.enterprise.iqk.service.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FORM_API 映射/重试/错误映射与参数 schema 校验（FR-R7-01/FR-R7-3）：
 * 用内建 HttpServer 做真实 HTTP（轻量，不引测试框架依赖），
 * 映射/重试语义与测试、运行时、审批执行同一入口锁死。
 */
class ToolInvocationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer server;
    private static String base;
    /** 5xx 端点被请求的次数（验证重试）。 */
    private static final AtomicInteger FLAKY_HITS = new AtomicInteger();

    private final ToolInvocationService service = new ToolInvocationService(
            null,
            new CodeToolSandboxService(MAPPER),
            MAPPER);

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rate", exchange -> {
            byte[] body = "{\"data\":{\"rate\":7.21,\"currency\":\"CNY\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/missing", exchange -> exchange.sendResponseHeaders(404, -1));
        server.createContext("/flaky", exchange -> {
            int hits = FLAKY_HITS.incrementAndGet();
            exchange.sendResponseHeaders(hits < 3 ? 500 : 200, -1);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void responseMappingExtractsDotPathsIntoResult() throws Exception {
        JsonNode config = json("""
                {"toolType":"FORM_API","method":"GET","url":"%s/rate",
                 "responseMapping":{"汇率":"data.rate","币种":"data.currency"}}""".formatted(base));

        Map<String, Object> result = service.invoke(config, Map.of());

        // 响应映射把上游 JSON 按点路径提取成精简输出，原始摘要保留
        assertThat(result.get("result")).isEqualTo(Map.of("汇率", 7.21, "币种", "CNY"));
        assertThat(String.valueOf(result.get("responseSummary"))).contains("rate");
    }

    @Test
    void errorMappingReplacesFailureMessageByStatusCode() throws Exception {
        JsonNode config = json("""
                {"toolType":"FORM_API","method":"GET","url":"%s/missing",
                 "errorMapping":{"404":"汇率配置不存在"}}""".formatted(base));

        Map<String, Object> result = service.invoke(config, Map.of());

        assertThat(result.get("status")).isEqualTo(404);
        assertThat(String.valueOf(result.get("responseSummary"))).contains("汇率配置不存在");
    }

    @Test
    void serverErrorsRetryUntilSuccess() throws Exception {
        FLAKY_HITS.set(0);
        JsonNode config = json("""
                {"toolType":"FORM_API","method":"GET","url":"%s/flaky","retry":3}""".formatted(base));

        Map<String, Object> result = service.invoke(config, Map.of());

        // 前 2 次 500，第 3 次 200：重试救回
        assertThat(result.get("status")).isEqualTo(200);
        assertThat(FLAKY_HITS.get()).isEqualTo(3);
    }

    @Test
    void schemaValidationRejectsMissingRequiredAndCoercesTypes() {
        JsonNode config = json("""
                {"toolType":"FORM_API","method":"GET","url":"x",
                 "params":[
                   {"name":"amount","type":"NUMBER","required":true},
                   {"name":"round","type":"BOOLEAN","defaultValue":"true"},
                   {"name":"memo","type":"STRING"}]}""");

        // 必填缺失拒绝
        assertThatThrownBy(() -> service.validateAgainstSchema(config, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
        // 字符串数字/布尔宽容转型 + 默认值回填
        Map<String, Object> validated = service.validateAgainstSchema(config, Map.of("amount", "3.5"));
        assertThat(validated.get("amount")).isEqualTo(3.5);
        assertThat(validated.get("round")).isEqualTo(true);
        // 类型不可解析拒绝
        assertThatThrownBy(() -> service.validateAgainstSchema(config, Map.of("amount", "abc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
