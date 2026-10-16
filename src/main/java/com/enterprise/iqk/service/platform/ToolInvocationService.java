package com.enterprise.iqk.service.platform;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 平台工具调用服务（配置态测试与聊天运行时共用，R7.3）：
 * FORM_API 按工具 config 真实发起 HTTP（PATH 替换/QUERY 拼接/BODY JSON/认证注入/超时钳制/16KB 截断）；
 * SQL 委托 {@link PlatformSqlTestService#execute}（只读守卫/租户占位/行数收敛）；
 * CODE_API 沙箱未接入显式拒绝。方法语义对"测试"与"运行时调用"完全一致——
 * 测试通过即可被智能体调用，不伪造结果。
 */
@Service
@RequiredArgsConstructor
public class ToolInvocationService {
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    /** 响应读取上限：防工具上游超大响应耗尽内存（观测回填还有 4000 字符的消毒截断兜底） */
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private final PlatformSqlTestService sqlTestService;
    private final ObjectMapper objectMapper;

    /** 按工具 config 执行一次调用：type 分发，返回 {status, responseSummary}（HTTP）或 SQL 结果。 */
    public Map<String, Object> invoke(JsonNode config, Map<String, Object> params) throws Exception {
        String type = config.path("toolType").asText("FORM_API").toUpperCase(Locale.ROOT);
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        return switch (type) {
            case "FORM_API" -> invokeHttpTool(config, safeParams);
            case "SQL" -> sqlTestService.execute(config, safeParams);
            case "CODE_API" -> throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "代码式工具沙箱尚未接入");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知工具类型：" + type);
        };
    }

    /** FORM_API 真实 HTTP 调用（原 PlatformToolTestService.testHttpTool 核心逻辑）。 */
    public Map<String, Object> invokeHttpTool(JsonNode config, Map<String, Object> params) throws Exception {
        String method = config.path("method").asText("GET").toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的 HTTP 方法：" + method);
        }
        String rawUrl = config.path("url").asText();
        if (rawUrl.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具 URL 不能为空");
        java.net.URL url = URI.create(substitutePath(rawUrl, params)).toURL();
        if (!("http".equalsIgnoreCase(url.getProtocol()) || "https".equalsIgnoreCase(url.getProtocol()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具 URL 仅支持 http/https");
        }

        StringBuilder query = new StringBuilder(url.getQuery() == null ? "" : url.getQuery());
        Map<String, Object> bodyValues = new LinkedHashMap<>();
        JsonNode definitions = config.path("params");
        if (definitions.isArray()) {
            for (JsonNode definition : definitions) {
                String name = definition.path("name").asText();
                if (name.isBlank() || !params.containsKey(name)) continue;
                String location = definition.path("location").asText("QUERY").toUpperCase(Locale.ROOT);
                String encoded = URLEncoder.encode(String.valueOf(params.get(name)), StandardCharsets.UTF_8);
                switch (location) {
                    case "QUERY" -> appendQuery(query, name + "=" + encoded);
                    case "PATH" -> { }
                    case "HEADER" -> { }
                    case "BODY" -> bodyValues.put(name, params.get(name));
                    default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "参数位置不合法：" + location);
                }
            }
        }
        String fullUrl = url.toString() + (query.isEmpty() ? "" : (url.getQuery() == null ? "?" : "&") + query);
        java.net.HttpURLConnection connection =
                (java.net.HttpURLConnection) URI.create(fullUrl).toURL().openConnection();
        int timeout = clampTimeout(config.path("timeoutMs").asInt(5000));
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(false);
        applyAuthorization(connection, config);
        if (!bodyValues.isEmpty()) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(objectMapper.writeValueAsString(bodyValues).getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        String response = readBounded(connection, MAX_RESPONSE_BYTES);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("responseSummary", response);
        return result;
    }

    /** 解析工具资产 config_json（非法配置统一 400，供运行时给出可修文案）。 */
    public JsonNode parseToolConfig(PlatformAsset tool) {
        try {
            JsonNode node = objectMapper.readTree(tool.getConfigJson() == null || tool.getConfigJson().isBlank()
                    ? "{}" : tool.getConfigJson());
            if (!node.isObject()) throw new IllegalArgumentException("tool config must be object");
            return node;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具配置 JSON 不合法", ex);
        }
    }

    private void applyAuthorization(java.net.HttpURLConnection connection, JsonNode config) {
        String authType = config.path("authType").asText("NONE").toUpperCase(Locale.ROOT);
        String credential = config.path("credential").asText();
        if (authType.equals("NONE") || credential.isBlank()) return;
        switch (authType) {
            case "BEARER" -> connection.setRequestProperty("Authorization", "Bearer " + credential);
            case "API_KEY_HEADER" -> connection.setRequestProperty("X-API-Key", credential);
            case "BASIC" -> connection.setRequestProperty("Authorization",
                    "Basic " + java.util.Base64.getEncoder()
                            .encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知认证方式：" + authType);
        }
    }

    private String substitutePath(String rawUrl, Map<String, Object> params) {
        String result = rawUrl;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String token = "{" + entry.getKey() + "}";
            result = result.replace(token, URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        return result;
    }

    private static void appendQuery(StringBuilder query, String pair) {
        if (!query.isEmpty()) query.append('&');
        query.append(pair);
    }

    private String readBounded(java.net.HttpURLConnection connection, int limit) throws IOException {
        try (InputStream input = connection.getResponseCode() >= 400
                ? connection.getErrorStream() : connection.getInputStream()) {
            if (input == null) return "";
            byte[] bytes = input.readNBytes(limit);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.length() == limit ? text + "...[truncated]" : text;
        }
    }

    private static int clampTimeout(int value) {
        return Math.max(500, Math.min(value, 60_000));
    }

    /** 观测用耗时换算。 */
    public static long elapsedMs(long startedNanos) {
        return Math.max(1, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }
}
