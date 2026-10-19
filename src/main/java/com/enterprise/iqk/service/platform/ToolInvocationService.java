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
 * FORM_API 按工具 config 真实发起 HTTP（PATH 替换/QUERY 拼接/BODY JSON/认证注入/超时钳制/
 * 失败重试/响应映射/错误映射/16KB 截断）；SQL 委托 {@link PlatformSqlTestService#execute}
 * （只读守卫/租户占位/行数收敛）；CODE_API 委托 {@link CodeToolSandboxService}（GraalJS 纯计算沙箱）。
 * 方法语义对"测试"与"运行时调用"完全一致——测试通过即可被智能体调用，不伪造结果。
 * 调用前统一按 params[] schema 校验（必填缺失拒绝、NUMBER/BOOLEAN 类型可解析、默认值回填）。
 */
@Service
@RequiredArgsConstructor
public class ToolInvocationService {
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    /** 响应读取上限：防工具上游超大响应耗尽内存（观测回填还有 4000 字符的消毒截断兜底） */
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private final PlatformSqlTestService sqlTestService;
    private final CodeToolSandboxService codeToolSandboxService;
    private final ObjectMapper objectMapper;

    /** 按工具 config 执行一次调用：先过参数 schema 校验再按 type 分发。 */
    public Map<String, Object> invoke(JsonNode config, Map<String, Object> params) throws Exception {
        String type = config.path("toolType").asText("FORM_API").toUpperCase(Locale.ROOT);
        Map<String, Object> validated = validateAgainstSchema(config, params);
        return switch (type) {
            case "FORM_API" -> invokeHttpTool(config, validated);
            case "SQL" -> sqlTestService.execute(config, validated);
            case "CODE_API" -> codeToolSandboxService.execute(config, validated);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知工具类型：" + type);
        };
    }

    /**
     * 参数 schema 校验（R7.3"模型输出参数按 schema 校验"）：必填缺失拒绝（默认值先回填），
     * NUMBER/BOOLEAN 需可解析（字符串数字宽容转成数值），其余类型透传。
     */
    public Map<String, Object> validateAgainstSchema(JsonNode config, Map<String, Object> params) {
        Map<String, Object> validated = new LinkedHashMap<>(params == null ? Map.of() : params);
        JsonNode definitions = config.path("params");
        if (!definitions.isArray()) {
            return validated;
        }
        for (JsonNode definition : definitions) {
            String name = definition.path("name").asText();
            if (name.isBlank()) continue;
            String type = definition.path("type").asText("STRING").toUpperCase(Locale.ROOT);
            boolean required = definition.path("required").asBoolean(false);
            if (!validated.containsKey(name) || validated.get(name) == null
                    || String.valueOf(validated.get(name)).isBlank()) {
                if (definition.hasNonNull("defaultValue") && !definition.path("defaultValue").asText().isBlank()) {
                    validated.put(name, definition.path("defaultValue").asText());
                } else if (required) {
                    throw new IllegalArgumentException("缺少必填参数：" + name);
                } else {
                    continue;
                }
            }
            Object value = validated.get(name);
            try {
                switch (type) {
                    case "NUMBER" -> validated.put(name, Double.valueOf(String.valueOf(value).trim()));
                    case "BOOLEAN" -> validated.put(name, Boolean.valueOf(
                            String.valueOf(value).trim().toLowerCase(Locale.ROOT)));
                    default -> {
                        // STRING/OBJECT/ARRAY 不深校验，透传
                    }
                }
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("参数 " + name + " 类型应为 " + type
                        + "，实际值不可解析：" + value);
            }
        }
        return validated;
    }

    /** FORM_API 真实 HTTP 调用：网络异常/5xx 按 retry 次数退避重试，终态走响应/错误映射。 */
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
        int timeout = clampTimeout(config.path("timeoutMs").asInt(5000));
        int attempts = Math.max(0, Math.min(config.path("retry").asInt(0), 5));
        IOException lastError = null;
        for (int attempt = 0; attempt <= attempts; attempt++) {
            if (attempt > 0) {
                Thread.sleep(200L * attempt); // 线性退避：200/400/600ms
            }
            try {
                java.net.HttpURLConnection connection =
                        (java.net.HttpURLConnection) URI.create(fullUrl).toURL().openConnection();
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
                // 5xx 视为瞬时故障参与重试；4xx 是请求侧问题重试无意义
                if (status >= 500 && attempt < attempts) {
                    continue;
                }
                return finalizeHttpResult(config, status, response);
            } catch (IOException ex) {
                lastError = ex;
                if (attempt == attempts) {
                    throw new IOException(mappedConnectionError(config, ex), ex);
                }
            }
        }
        throw lastError == null ? new IOException("工具调用失败") : lastError;
    }

    /** 终态收口：错误映射按状态码覆盖文案；响应映射从 JSON 响应提取输出字段（仅 2xx/3xx 且可解析）。 */
    private Map<String, Object> finalizeHttpResult(JsonNode config, int status, String response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        if (status >= 400) {
            JsonNode mapped = config.path("errorMapping").path(String.valueOf(status));
            result.put("responseSummary", mapped.isTextual() && !mapped.asText().isBlank()
                    ? mapped.asText() + "（HTTP " + status + "）" : response);
            return result;
        }
        result.put("responseSummary", response);
        JsonNode mapping = config.path("responseMapping");
        if (mapping.isObject() && mapping.size() > 0) {
            Map<String, Object> extracted = new LinkedHashMap<>();
            mapping.fields().forEachRemaining(field -> {
                JsonNode value = extractPath(response, field.getValue().asText());
                if (!value.isMissingNode() && !value.isNull()) {
                    extracted.put(field.getKey(), objectMapper.convertValue(value, Object.class));
                }
            });
            if (!extracted.isEmpty()) {
                result.put("result", extracted);
            }
        }
        return result;
    }

    /** 极简点路径提取（支持 a.b.0.c 与 name[0] 段），仅作用于可解析的 JSON 响应；不可解析返回 missing。 */
    private JsonNode extractPath(String responseJson, String path) {
        if (path == null || path.isBlank()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        try {
            JsonNode current = objectMapper.readTree(responseJson);
            for (String segment : path.replaceAll("\\[(\\d+)]", ".$1").split("\\.")) {
                if (segment.isBlank()) continue;
                if (current.isArray() && segment.matches("\\d+")) {
                    current = current.get(Integer.parseInt(segment));
                } else {
                    current = current.path(segment);
                }
                if (current == null || current.isMissingNode()) {
                    return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
                }
            }
            return current;
        } catch (IOException ex) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
    }

    /** 连接层异常映射：errorMapping["connection"] 存在则替换为友好文案。 */
    private String mappedConnectionError(JsonNode config, IOException ex) {
        JsonNode mapped = config.path("errorMapping").path("connection");
        String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return mapped.isTextual() && !mapped.asText().isBlank()
                ? mapped.asText() + "（" + reason + "）" : "工具上游不可达：" + reason;
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
