package com.enterprise.iqk.service.platform;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.enterprise.iqk.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 平台工具测试：表单式 API 真实探测；SQL 只读参数化执行；代码式显式不支持。 */
@Service
@RequiredArgsConstructor
public class PlatformToolTestService {
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final PlatformAssetMapper assetMapper;
    private final PlatformSqlTestService sqlTestService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> test(Long id, Map<String, Object> params) {
        PlatformAsset tool = ownedTool(id);
        JsonNode config = parse(tool.getConfigJson());
        String type = config.path("toolType").asText("FORM_API").toUpperCase(Locale.ROOT);
        long started = System.nanoTime();
        try {
            Map<String, Object> result = switch (type) {
                case "FORM_API" -> testHttpTool(config, params == null ? Map.of() : params);
                case "SQL" -> sqlTestService.execute(config, params == null ? Map.of() : params);
                case "CODE_API" -> throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                        "代码式工具沙箱尚未接入，不能伪造测试结果");
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知工具类型：" + type);
            };
            result.put("latencyMs", elapsedMs(started));
            return result;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("status", "ERROR");
            failure.put("latencyMs", elapsedMs(started));
            failure.put("error", safeMessage(ex));
            return failure;
        }
    }

    private Map<String, Object> testHttpTool(JsonNode config, Map<String, Object> params) throws Exception {
        String method = config.path("method").asText("GET").toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的 HTTP 方法：" + method);
        }
        String rawUrl = config.path("url").asText();
        if (rawUrl.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具 URL 不能为空");
        URL url = URI.create(substitutePath(rawUrl, params)).toURL();
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
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) URI.create(fullUrl).toURL().openConnection();
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
        String response = readBounded(connection, 16 * 1024);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("responseSummary", response);
        return result;
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
        try (java.io.InputStream input = connection.getResponseCode() >= 400
                ? connection.getErrorStream() : connection.getInputStream()) {
            if (input == null) return "";
            byte[] bytes = input.readNBytes(limit);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.length() == limit ? text + "...[truncated]" : text;
        }
    }

    private PlatformAsset ownedTool(Long id) {
        PlatformAsset tool = assetMapper.findByIdAndType(id, TenantContext.currentTenantId(), "tools");
        if (tool == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在");
        return tool;
    }

    private JsonNode parse(String json) {
        try {
            JsonNode node = objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
            if (!node.isObject()) throw new IllegalArgumentException("tool config must be object");
            return node;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具配置 JSON 不合法", ex);
        }
    }

    private static int clampTimeout(int value) {
        return Math.max(500, Math.min(value, 60_000));
    }

    private static long elapsedMs(long started) {
        return Math.max(1, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "工具测试失败" : message;
    }
}
