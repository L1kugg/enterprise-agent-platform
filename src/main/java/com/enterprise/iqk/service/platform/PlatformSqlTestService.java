package com.enterprise.iqk.service.platform;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.tools.SqlReadOnlyGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SQL 工具测试：只读守卫 + 参数绑定 + 行数/超时/单元格截断四层防护。 */
@Service
@RequiredArgsConstructor
public class PlatformSqlTestService {
    private static final Pattern NAMED_PARAMETER = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{1,63}$");
    private static final Set<String> DENIED_TABLES = Set.of(
            "users", "api_keys", "refresh_tokens", "roles", "permissions", "role_permissions", "user_roles");

    private final PlatformAssetMapper assetMapper;
    private final ObjectMapper objectMapper;
    /** 凭据解密（FR-R12-01）：数据库密码字段在执行/测试前解密 */
    private final com.enterprise.iqk.security.SecretCipher secretCipher;

    public Map<String, Object> testDatabase(Long id) {
        PlatformAsset database = ownedDatabase(id);
        JsonNode encrypted = parseRaw(database.getConfigJson());
        JsonNode config = secretCipher.transformFields(encrypted, false);
        long started = System.nanoTime();
        try (Connection ignored = open(config)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "AVAILABLE");
            result.put("latencyMs", elapsedMs(started));
            persistDatabaseStatus(database, encrypted, "AVAILABLE", elapsedMs(started));
            return result;
        } catch (Exception ex) {
            long elapsed = elapsedMs(started);
            persistDatabaseStatus(database, encrypted, "UNAVAILABLE", elapsed);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "UNAVAILABLE");
            result.put("latencyMs", elapsed);
            result.put("error", "数据库连接失败，请检查地址、账号、密码和网络访问权限");
            return result;
        }
    }

    public Map<String, Object> execute(JsonNode toolConfig, Map<String, Object> params) throws Exception {
        long databaseId = toolConfig.path("databaseId").asLong();
        PlatformAsset database = ownedDatabase(databaseId);
        JsonNode databaseConfig = parse(database.getConfigJson());
        if (!databaseConfig.path("readOnly").asBoolean(true)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SQL 工具只能绑定只读数据库");
        }
        String rawSql = toolConfig.path("sql").asText();
        int maxRows = Math.max(1, Math.min(toolConfig.path("maxRows").asInt(100), 500));
        SqlReadOnlyGuard.Result guard = SqlReadOnlyGuard.validate(rawSql, 20_000, maxRows, DENIED_TABLES);
        if (!guard.allowed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, guard.reasonMessage());
        }

        List<String> names = new ArrayList<>();
        Matcher matcher = NAMED_PARAMETER.matcher(rawSql);
        StringBuilder positional = new StringBuilder();
        while (matcher.find()) {
            names.add(matcher.group(1));
            matcher.appendReplacement(positional, "?");
        }
        matcher.appendTail(positional);

        try (Connection connection = open(databaseConfig)) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(positional.toString(),
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                statement.setQueryTimeout(Math.max(1, Math.min(toolConfig.path("timeoutMs").asInt(5000) / 1000, 30)));
                for (int i = 0; i < names.size(); i++) {
                    Object value = params.get(names.get(i));
                    if (value == null) {
                        statement.setObject(i + 1, null);
                    } else if (value instanceof Number number) {
                        statement.setObject(i + 1, number);
                    } else if (value instanceof Boolean bool) {
                        statement.setBoolean(i + 1, bool);
                    } else {
                        statement.setString(i + 1, String.valueOf(value));
                    }
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    ResultSetMetaData metadata = resultSet.getMetaData();
                    List<String> columns = new ArrayList<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        columns.add(metadata.getColumnName(i));
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (resultSet.next() && rows.size() < maxRows) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columns.size(); i++) {
                            row.put(columns.get(i - 1), truncate(resultSet.getObject(i)));
                        }
                        rows.add(row);
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", 200);
                    result.put("responseSummary", objectMapper.writeValueAsString(Map.of(
                            "columns", columns, "rowCount", rows.size(), "rows", rows)));
                    return result;
                }
            }
        }
    }
    private Connection open(JsonNode config) throws Exception {
        String type = config.path("databaseType").asText("MYSQL").toUpperCase();
        String host = requiredText(config, "host", "数据库主机");
        int port = config.path("port").asInt(type.equals("POSTGRESQL") ? 5432 : 3306);
        String databaseName = requiredText(config, "databaseName", "数据库名");
        String username = requiredText(config, "username", "数据库用户名");
        String password = config.path("password").asText();
        String jdbcUrl = switch (type) {
            case "MYSQL", "MARIADB" -> "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=10000"
                    .formatted(host, port, databaseName);
            case "POSTGRESQL" -> "jdbc:postgresql://%s:%d/%s?connectTimeout=5&socketTimeout=10"
                    .formatted(host, port, databaseName);
            case "SQLSERVER" -> "jdbc:sqlserver://%s:%d;databaseName=%s;loginTimeout=5;socketTimeout=10000"
                    .formatted(host, port, databaseName);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "暂不支持的数据库类型：" + type);
        };
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        return DriverManager.getConnection(jdbcUrl, properties);
    }

    private PlatformAsset ownedDatabase(Long id) {
        // 系统共享资产可见（scope=SYSTEM）：本租户私有 ∪ 系统共享
        PlatformAsset database = assetMapper.findByIdAndTypeVisible(id, TenantContext.currentTenantId(), "databases");
        if (database == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据库配置不存在");
        return database;
    }

    private JsonNode parse(String json) {
        return secretCipher.transformFields(parseRaw(json), false);
    }

    private JsonNode parseRaw(String json) {
        try {
            JsonNode node = objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
            if (!node.isObject()) throw new IllegalArgumentException("database config must be object");
            return node;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据库配置 JSON 不合法", ex);
        }
    }

    private void persistDatabaseStatus(PlatformAsset database, JsonNode current, String status, long latency) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode config = (com.fasterxml.jackson.databind.node.ObjectNode)
                    current.deepCopy();
            config.put("lastTestStatus", status);
            config.put("lastTestLatencyMs", latency);
            config.put("lastTestedAt", LocalDateTime.now().toString());
            database.setStatus(status.equals("AVAILABLE") ? "AVAILABLE" : "UNAVAILABLE");
            database.setConfigJson(objectMapper.writeValueAsString(config));
            database.setUpdatedAt(LocalDateTime.now());
            assetMapper.updateById(database);
        } catch (IOException ignored) {
            // 状态落库失败不影响测试结果返回；下一次测试仍可刷新
        }
    }

    private static Object truncate(Object value) {
        if (value instanceof String text && text.length() > 512) return text.substring(0, 512) + "...[truncated]";
        return value;
    }

    private static String requiredText(JsonNode config, String field, String label) {
        String value = config.path(field).asText();
        if (value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空");
        return value;
    }

    private static long elapsedMs(long started) {
        return Math.max(1, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }
}
