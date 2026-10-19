package com.enterprise.iqk.service.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CODE_API 沙箱语义（FR-R7-02）：纯计算可用，输入注入/返回值回收按契约，
 * 无主机访问、死循环被语句数/超时拦下，非 JavaScript 明确拒绝。
 */
class CodeToolSandboxServiceTest {

    private final CodeToolSandboxService sandbox = new CodeToolSandboxService(new ObjectMapper());

    @Test
    void executesPureComputationWithInjectedInputAndReturnedOutput() throws Exception {
        Map<String, Object> result = sandbox.execute(
                json("""
                        {"toolType":"CODE_API","language":"JAVASCRIPT","timeoutMs":3000,
                         "code":"const amount = Number(input.amount); return {cny: amount * Number(input.rate), memo: input.memo};"}"""),
                Map.of("amount", 100, "rate", "7.21", "memo", "测试"));

        assertThat(result.get("status")).isEqualTo("sandbox");
        assertThat(String.valueOf(result.get("output")))
                .contains("\"cny\":721")
                .contains("\"memo\":\"测试\"");
    }

    @Test
    void outputVariableIsAcceptedWhenNoReturn() throws Exception {
        Map<String, Object> result = sandbox.execute(
                json("{\"toolType\":\"CODE_API\",\"language\":\"JAVASCRIPT\",\"code\":"
                        + "\"output = input.a + input.b;\"}"),
                Map.of("a", 1, "b", 2));

        assertThat(String.valueOf(result.get("output"))).contains("3");
    }

    @Test
    void infiniteLoopIsInterruptedByTimeout() {
        String code = "{\"toolType\":\"CODE_API\",\"language\":\"JAVASCRIPT\",\"timeoutMs\":400,"
                + "\"code\":\"while (true) { }\"}";

        assertThatThrownBy(() -> sandbox.execute(json(code), Map.of()))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getReason()).contains("上限"));
    }

    @Test
    void hostAccessIsDeniedInsideSandbox() {
        // 尝试触碰 Java 类：沙箱下必须直接报错，而不是返回宿主对象
        String code = "{\"toolType\":\"CODE_API\",\"language\":\"JAVASCRIPT\",\"timeoutMs\":2000,"
                + "\"code\":\"return Java.type('java.lang.Runtime');\"}";

        assertThatThrownBy(() -> sandbox.execute(json(code), Map.of()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void nonJavascriptLanguageIsRejected() {
        String code = "{\"toolType\":\"CODE_API\",\"language\":\"PYTHON\",\"code\":\"pass\"}";

        assertThatThrownBy(() -> sandbox.execute(json(code), Map.of()))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getReason()).contains("JavaScript"));
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String text) {
        try {
            return new ObjectMapper().readTree(text);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
