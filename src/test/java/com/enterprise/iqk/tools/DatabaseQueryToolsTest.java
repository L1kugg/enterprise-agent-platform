package com.enterprise.iqk.tools;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DatabaseQueryTools 单测：mock JDBC 链路，锁定停用短路、守卫拒绝不触库、
 * 租户启发式提示、只读执行参数（setReadOnly/queryTimeout/maxRows）与 payload 形状。
 * ConnectionCallback 内部通过 mock Connection/Statement/ResultSet 回放。
 */
class DatabaseQueryToolsTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AgentHarnessProperties properties = new AgentHarnessProperties();

    private DatabaseQueryTools newTools() {
        return new DatabaseQueryTools(jdbcTemplate, new SimpleMeterRegistry(), properties);
    }

    @Test
    void shortCircuitsWithBusinessErrorWhenDisabled() {
        properties.getDatabaseQuery().setEnabled(false);

        Map<String, Object> payload = newTools().queryDatabase("SELECT 1", "public");

        assertThat(payload).containsEntry("status", "error");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void guardRejectionNeverTouchesDatabase() {
        Map<String, Object> payload = newTools().queryDatabase("SELECT 1; DROP TABLE course", "public");

        assertThat(payload).containsEntry("status", "error");
        assertThat((String) payload.get("message")).contains("只允许一条 SELECT");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void missingTenantFilterReturnsCorrectiveHintWithTenantValue() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of("course"));

        Map<String, Object> payload = newTools().queryDatabase("SELECT * FROM course", "public");

        assertThat(payload).containsEntry("status", "error");
        assertThat((String) payload.get("message"))
                .contains("缺少租户过滤")
                .contains("course")
                .contains("tenant_id = 'public'");
        // 启发式查 information_schema 属合法交互；拦截点在执行层（execute 只读查询未发生）
        verify(jdbcTemplate).queryForList(anyString(), eq(String.class));
        verify(jdbcTemplate, never()).execute(any(ConnectionCallback.class));
    }

    @Test
    void executesReadOnlyWithDriverLevelCapsAndReturnsShapedPayload() throws Exception {
        properties.getDatabaseQuery().setMaxCellChars(3);
        Connection con = mock(Connection.class);
        Statement st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(con.createStatement()).thenReturn(st);
        when(st.executeQuery("SELECT id, name FROM course WHERE tenant_id = 'public' LIMIT 30"))
                .thenReturn(rs);
        when(rs.getMetaData()).thenReturn(md);
        when(md.getColumnCount()).thenReturn(2);
        when(md.getColumnLabel(1)).thenReturn("id");
        when(md.getColumnLabel(2)).thenReturn("name");
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString(1)).thenReturn("1", "2");
        when(rs.getString(2)).thenReturn("Java 入门", null);
        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                .thenAnswer(invocation -> ((ConnectionCallback<?>) invocation.getArgument(0)).doInConnection(con));

        Map<String, Object> payload = newTools().queryDatabase(
                "SELECT id, name FROM course WHERE tenant_id = 'public'", "public");

        // 只读会话与驱动级硬顶：连接只读、语句超时、maxRows+1 探测截断
        verify(con).setReadOnly(true);
        verify(con).setReadOnly(false);
        verify(st).setQueryTimeout(5);
        verify(st).setMaxRows(31);
        assertThat(payload)
                .containsEntry("columns", List.of("id", "name"))
                .containsEntry("rowCount", 2)
                .containsEntry("truncated", false)
                .containsEntry("executedSql", "SELECT id, name FROM course WHERE tenant_id = 'public' LIMIT 30");
        // null 单元格以显式 null 值保留在行 Map 里（JSON null），列序稳定
        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("id", "1");
        row1.put("name", "Jav…");
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("id", "2");
        row2.put("name", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.get("rows");
        assertThat(rows).containsExactly(row1, row2);
    }

    @Test
    void convertsSqlExecutionFailureIntoRetryableErrorObservation() {
        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                .thenThrow(new QueryTimeoutException("statement timed out"));

        Map<String, Object> payload = newTools().queryDatabase(
                "SELECT id FROM course WHERE tenant_id = 'public'", "public");

        assertThat(payload).containsEntry("status", "error");
        assertThat((String) payload.get("message"))
                .contains("查询执行失败")
                .contains("statement timed out");
    }

    @Test
    void emptySqlIsRejectedBeforeGuard() {
        Map<String, Object> payload = newTools().queryDatabase("  ", "public");

        assertThat(payload).containsEntry("status", "error");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void tenantHeuristicSkipsWhenSqlAlreadyFiltersTenant() {
        // SQL 已带 tenant_id 时跳过租户启发式直接执行：
        // 错误消息是执行层报错而非“缺少租户过滤”，即证明启发式被跳过
        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                .thenThrow(new QueryTimeoutException("reached execution layer"));

        Map<String, Object> payload = newTools().queryDatabase(
                "SELECT * FROM course WHERE tenant_id = 'public'", "public");

        assertThat((String) payload.get("message")).contains("reached execution layer");
    }
}
