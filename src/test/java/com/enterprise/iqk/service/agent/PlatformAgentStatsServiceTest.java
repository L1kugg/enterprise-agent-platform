package com.enterprise.iqk.service.agent;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.AgentInvocationLogMapper;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 观测看板服务（R2.2）：数字全部来自真实聚合——空数据显式零值、成功率按总数归一、
 * 趋势缺天补零、非法过滤归一、不可见智能体 404。
 */
@ExtendWith(MockitoExtension.class)
class PlatformAgentStatsServiceTest {

    @Mock
    private PlatformAssetMapper assetMapper;

    @Mock
    private AgentInvocationLogMapper invocationLogMapper;

    private PlatformAgentStatsService service;

    @BeforeEach
    void setUp() {
        // mock 注入完成后构造（字段初始化器拿不到注入的 mock）
        service = new PlatformAgentStatsService(assetMapper, invocationLogMapper);
    }

    @Test
    void invisibleAgentReturns404() {
        when(assetMapper.findByIdAndTypeVisible(9L, "t-1", "agents")).thenReturn(null);

        assertThatThrownBy(() -> service.stats("t-1", 9L, 7))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void emptyDataReturnsExplicitZeroState() {
        when(assetMapper.findByIdAndTypeVisible(eq(1L), eq("t-1"), eq("agents"))).thenReturn(agent());
        when(invocationLogMapper.summarizeByAgent(eq("t-1"), eq(1L), any())).thenReturn(null);
        when(invocationLogMapper.topErrorsByAgent(eq("t-1"), eq(1L), any(), anyInt())).thenReturn(List.of());

        Map<String, Object> stats = service.stats("t-1", 1L, 7);

        assertThat(stats.get("total")).isEqualTo(0L);
        assertThat(stats.get("successRate")).isEqualTo(0.0);
        assertThat(stats.get("avgLatencyMs")).isEqualTo(0L);
        assertThat((List<?>) stats.get("topErrors")).isEmpty();
    }

    @Test
    void statsComputeSuccessRateAndTokenSums() {
        when(assetMapper.findByIdAndTypeVisible(eq(1L), eq("t-1"), eq("agents"))).thenReturn(agent());
        Map<String, Object> raw = new HashMap<>();
        raw.put("total", 10L);
        raw.put("success_count", 8L);
        raw.put("blocked_count", 1L);
        raw.put("error_count", 1L);
        raw.put("avg_latency_ms", 1234L);
        raw.put("max_latency_ms", 9000L);
        raw.put("input_tokens", 1500L);
        raw.put("output_tokens", 300L);
        raw.put("fallback_count", 2L);
        when(invocationLogMapper.summarizeByAgent(eq("t-1"), eq(1L), any())).thenReturn(raw);
        when(invocationLogMapper.topErrorsByAgent(eq("t-1"), eq(1L), any(), anyInt())).thenReturn(List.of(
                Map.of("error_summary", "绑定模型调用失败", "cnt", 1L)));

        Map<String, Object> stats = service.stats("t-1", 1L, 7);

        assertThat(stats.get("successRate")).isEqualTo(0.8);
        assertThat(stats.get("inputTokens")).isEqualTo(1500L);
        assertThat(stats.get("fallbackCount")).isEqualTo(2L);
        assertThat((List<?>) stats.get("topErrors")).hasSize(1);
    }

    @Test
    void trendFillsMissingDaysWithZeros() {
        when(assetMapper.findByIdAndTypeVisible(eq(1L), eq("t-1"), eq("agents"))).thenReturn(agent());
        LocalDate today = LocalDate.now();
        Map<String, Object> onlyToday = new HashMap<>();
        onlyToday.put("day", java.sql.Date.valueOf(today));
        onlyToday.put("total", 5L);
        onlyToday.put("success_count", 4L);
        onlyToday.put("blocked_count", 0L);
        onlyToday.put("error_count", 1L);
        onlyToday.put("avg_latency_ms", 800L);
        when(invocationLogMapper.trendByAgent(eq("t-1"), eq(1L), any())).thenReturn(List.of(onlyToday));

        List<Map<String, Object>> points = service.trend("t-1", 1L, 3);

        // 3 天窗口：缺失的前两天补零，最后一天是真数据
        assertThat(points).hasSize(3);
        assertThat(points.get(0).get("total")).isEqualTo(0L);
        assertThat(points.get(1).get("total")).isEqualTo(0L);
        assertThat(points.get(2).get("total")).isEqualTo(5L);
        assertThat(points.get(2).get("avgLatencyMs")).isEqualTo(800L);
    }

    @Test
    void logFiltersNormalizeIllegalValuesAndClampPage() {
        when(assetMapper.findByIdAndTypeVisible(eq(1L), eq("t-1"), eq("agents"))).thenReturn(agent());
        when(invocationLogMapper.countByAgent(eq("t-1"), eq(1L), isNull(), isNull())).thenReturn(0L);

        // 非法 status/channel 归一为不过滤；页码/页大小钳正
        Map<String, Object> result = service.logs("t-1", 1L, -3, 500, "HACK", "wechat");

        assertThat(result.get("page")).isEqualTo(1);
        assertThat(result.get("pageSize")).isEqualTo(100);
        verify(invocationLogMapper).countByAgent("t-1", 1L, null, null);
    }

    private PlatformAsset agent() {
        return PlatformAsset.builder()
                .id(1L).tenantId("t-1").assetType("agents").name("助手")
                .status("PUBLISHED").build();
    }
}
