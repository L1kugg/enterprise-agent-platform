package com.enterprise.iqk.tools;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 主库只读查询工具（query_database 动作的执行端）：SQL 由模型现场生成，
 * 经 SqlReadOnlyGuard 校验归一化后，在只读会话上对主库 MySQL 执行并回传行数据。
 *
 * <p>四层防御：① 只读守卫（写关键词/多语句/敏感表黑名单/LIMIT 收敛）
 * → ② 租户过滤启发式（业务表带 tenant_id 列而 SQL 未过滤时拒绝并给出确切改法）
 * → ③ 连接级只读 + 查询超时 → ④ 驱动级行数硬顶 + 单元格截断。
 * 租户值只出现在给模型的错误提示里，从不拼进执行的 SQL。
 *
 * <p>注入的 JdbcTemplate 是 Spring Boot 按主库 DataSource 自动装配的唯一 bean
 * （pgvector 的 JdbcTemplate 在 VectorStoreConfiguration 内部构建、不是 bean，勿混淆）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseQueryTools {

    private static final String TOOL_NAME = "query_database";
    /** 租户列缓存 TTL：information_schema 查询不便宜，5 分钟内复用 */
    private static final long TENANT_TABLES_CACHE_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);
    /** 错误消息里数据库原始报错的最大长度（模型观测预算有限，够定位即可） */
    private static final int DB_ERROR_MESSAGE_MAX_CHARS = 300;

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final AgentHarnessProperties harnessProperties;

    /** 含 tenant_id 列的表名缓存（记录 + 装载时刻，TTL 过期重查） */
    private final AtomicReference<TenantTableCache> tenantTableCache = new AtomicReference<>();

    private record TenantTableCache(Set<String> tables, long loadedAtNanos) {
    }

    /**
     * 执行一条只读查询：返回 status=error 的 Map 表示业务拒绝（守卫/租户启发式/停用），
     * 与 BuiltinToolRuntime 的 error 观测转换约定一致；异常（连接/驱动层）原样上抛由 harness 兜底。
     * tenantId 为服务端实参（调用方从 action.tenantId() 归一化取得），绝不是模型入参。
     */
    public Map<String, Object> queryDatabase(String sql, String tenantId) {
        AgentHarnessProperties.DatabaseQuery config = harnessProperties.getDatabaseQuery();
        if (!config.isEnabled()) {
            return errorMap("query_database 动作已停用（app.agent-harness.database-query.enabled）。");
        }
        return instrumentedQuery(sql, tenantId, config);
    }

    /** 全程计时模板：守卫/租户拒绝与异常都计 error 状态，与 CourseTools 的 tool.query.latency 同构。 */
    private Map<String, Object> instrumentedQuery(String sql, String tenantId,
                                                  AgentHarnessProperties.DatabaseQuery config) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String status = "success";
        try {
            Map<String, Object> payload = runQuery(sql, tenantId, config);
            if ("error".equals(payload.get("status"))) {
                status = "error";
            }
            return payload;
        } catch (RuntimeException ex) {
            status = "error";
            throw ex;
        } finally {
            sample.stop(Timer.builder("tool.query.latency")
                    .description("Latency for tool-layer query and write operations")
                    .tag("tool", TOOL_NAME)
                    .tag("status", status)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    /** 守卫 → 租户启发式 → 只读执行 的主流程。 */
    private Map<String, Object> runQuery(String sql, String tenantId,
                                         AgentHarnessProperties.DatabaseQuery config) {
        if (!StringUtils.hasText(sql)) {
            return errorMap("SQL 不能为空：action_input 需为 {\"sql\":\"SELECT ...\"}。");
        }
        SqlReadOnlyGuard.Result guard = SqlReadOnlyGuard.validate(
                sql, config.getMaxSqlLength(), config.getMaxRows(), config.getDeniedTables());
        if (!guard.allowed()) {
            log.info("只读守卫拒绝: reason={}, sql={}", guard.reasonCode(), abbreviate(sql, 200));
            return errorMap(guard.reasonMessage());
        }

        String tenantHint = missingTenantMessage(guard.normalizedSql(), tenantId);
        if (tenantHint != null) {
            return errorMap(tenantHint);
        }

        try {
            return executeReadOnly(guard.normalizedSql(), config);
        } catch (DataAccessException ex) {
            // SQL 语法/列名错误等属模型可自行修正的问题：转成带原始报错的 error 观测供重试，
            // 不升级成整次回答的异常
            String cause = abbreviate(String.valueOf(ex.getMostSpecificCause().getMessage()),
                    DB_ERROR_MESSAGE_MAX_CHARS);
            log.warn("只读查询执行失败: sql={}, reason={}", guard.normalizedSql(), ex.toString());
            return errorMap("查询执行失败，请根据数据库报错修正 SQL 后重试：" + cause);
        }
    }

    /**
     * 租户过滤启发式：提取 FROM/JOIN 的表，与含 tenant_id 列的表求交集，
     * 命中且 SQL 未出现 tenant_id 时返回修正提示（null 表示放行）。
     * 刻意 fail-open：缓存查不到时跳过检查——本检查是提示性防线，
     * 强约束靠提示词要求与模型重试闭环，不是硬隔离。
     */
    private String missingTenantMessage(String normalizedSql, String tenantId) {
        String lower = normalizedSql.toLowerCase(Locale.ROOT);
        if (lower.contains("tenant_id")) {
            return null;
        }
        Set<String> tenantTables = tablesWithTenantColumn();
        if (tenantTables.isEmpty()) {
            return null;
        }
        List<String> hits = new ArrayList<>();
        for (String table : SqlReadOnlyGuard.extractTableNames(normalizedSql)) {
            if (tenantTables.contains(table)) {
                hits.add(table);
            }
        }
        if (hits.isEmpty()) {
            return null;
        }
        // 租户值经单引号转义后仅出现在提示里，从不拼进执行的 SQL
        String safeTenant = tenantId == null ? "public" : tenantId.replace("'", "''");
        return "缺少租户过滤：表 " + String.join("、", hits)
                + " 含 tenant_id 列，请在 WHERE 中加上 tenant_id = '" + safeTenant + "' 后重试。";
    }

    /** 含 tenant_id 列的表名集合（小写），带 TTL 缓存；查询失败不缓存、静默按空集处理。 */
    private Set<String> tablesWithTenantColumn() {
        TenantTableCache cached = tenantTableCache.get();
        if (cached != null && System.nanoTime() - cached.loadedAtNanos() < TENANT_TABLES_CACHE_TTL_NANOS) {
            return cached.tables();
        }
        Set<String> tables;
        try {
            tables = Set.copyOf(jdbcTemplate.queryForList(
                    "SELECT table_name FROM information_schema.columns"
                            + " WHERE table_schema = DATABASE() AND column_name = 'tenant_id'",
                    String.class));
        } catch (RuntimeException ex) {
            log.warn("租户列信息查询失败（本次跳过租户启发式）: {}", ex.toString());
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String table : tables) {
            normalized.add(table == null ? "" : table.toLowerCase(Locale.ROOT));
        }
        tenantTableCache.set(new TenantTableCache(Set.copyOf(normalized), System.nanoTime()));
        return Set.copyOf(normalized);
    }

    /**
     * 只读会话执行：连接设只读（Connector/J 传播为会话级 READ ONLY，是绕过守卫时的服务器端兜底），
     * 语句设超时与驱动级行数硬顶（多取 1 行探测截断）。列序/行序用 LinkedHashMap 保持稳定。
     */
    private Map<String, Object> executeReadOnly(String normalizedSql,
                                                AgentHarnessProperties.DatabaseQuery config)
            throws DataAccessException {
        return jdbcTemplate.execute((java.sql.Connection con) -> {
            con.setReadOnly(true);
            try (java.sql.Statement st = con.createStatement()) {
                st.setQueryTimeout(config.getQueryTimeoutSeconds());
                st.setMaxRows(config.getMaxRows() + 1);
                try (java.sql.ResultSet rs = st.executeQuery(normalizedSql)) {
                    java.sql.ResultSetMetaData md = rs.getMetaData();
                    int columnCount = md.getColumnCount();
                    List<String> columns = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        columns.add(md.getColumnLabel(i));
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (rs.next()) {
                        if (rows.size() >= config.getMaxRows()) {
                            truncated = true;
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            row.put(columns.get(i - 1), truncateCell(rs.getString(i), config.getMaxCellChars()));
                        }
                        rows.add(row);
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("columns", columns);
                    payload.put("rows", rows);
                    payload.put("rowCount", rows.size());
                    payload.put("truncated", truncated);
                    payload.put("executedSql", normalizedSql);
                    return payload;
                } finally {
                    con.setReadOnly(false);
                }
            }
        });
    }

    /** 单元格截断：超长部分以省略号结尾；null 原样保留（JSON null）。 */
    private String truncateCell(String value, int maxCellChars) {
        if (value == null || value.length() <= maxCellChars) {
            return value;
        }
        return value.substring(0, maxCellChars) + "…";
    }

    /** status=error 的业务拒绝载荷（BuiltinToolRuntime 据此转 error 观测）。 */
    private Map<String, Object> errorMap(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "error");
        payload.put("message", message);
        return payload;
    }

    private String abbreviate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…";
    }
}
