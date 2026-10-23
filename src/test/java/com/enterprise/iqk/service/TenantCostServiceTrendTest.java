package com.enterprise.iqk.service;

import com.enterprise.iqk.config.properties.CostGovernanceProperties;
import com.enterprise.iqk.domain.TenantUsageDaily;
import com.enterprise.iqk.domain.TenantUserUsageDaily;
import com.enterprise.iqk.domain.vo.TenantCostTrendVO;
import com.enterprise.iqk.mapper.TenantBudgetMapper;
import com.enterprise.iqk.mapper.TenantUsageDailyMapper;
import com.enterprise.iqk.mapper.TenantUserUsageDailyMapper;
import com.enterprise.iqk.mapper.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantCostServiceTrendTest {

    private TenantUsageDailyMapper usageMapper;
    private TenantBudgetMapper budgetMapper;
    private TenantUserUsageDailyMapper userUsageMapper;
    private UserAccountMapper userAccountMapper;
    private TenantCostService service;

    @BeforeEach
    void setUp() {
        usageMapper = mock(TenantUsageDailyMapper.class);
        budgetMapper = mock(TenantBudgetMapper.class);
        userUsageMapper = mock(TenantUserUsageDailyMapper.class);
        userAccountMapper = mock(UserAccountMapper.class);
        service = new TenantCostService(budgetMapper, usageMapper, userUsageMapper,
                userAccountMapper, new CostGovernanceProperties());
    }

    @Test
    void trendZeroFillsMissingDays() {
        LocalDate today = LocalDate.now();
        when(usageMapper.findByTenantIdAndDateRange(eq("tenant-1"), any(), any()))
                .thenReturn(List.of(TenantUsageDaily.builder()
                        .usageDate(today.minusDays(2))
                        .requestCount(3L)
                        .inputTokens(100L)
                        .outputTokens(50L)
                        .totalCostUsd(new BigDecimal("0.4500"))
                        .build()));

        List<TenantCostTrendVO> result = service.trend("tenant-1", 7);

        assertThat(result).hasSize(7);
        assertThat(result.get(0).getDate()).isEqualTo(today.minusDays(6).toString());
        assertThat(result.get(6).getDate()).isEqualTo(today.toString());

        TenantCostTrendVO hit = result.get(4);
        assertThat(hit.getRequestCount()).isEqualTo(3L);
        assertThat(hit.getInputTokens()).isEqualTo(100L);
        assertThat(hit.getOutputTokens()).isEqualTo(50L);
        assertThat(hit.getCostUsd()).isEqualByComparingTo("0.4500");

        TenantCostTrendVO empty = result.get(0);
        assertThat(empty.getRequestCount()).isZero();
        assertThat(empty.getInputTokens()).isZero();
        assertThat(empty.getOutputTokens()).isZero();
        assertThat(empty.getCostUsd()).isEqualByComparingTo("0.0000");
    }

    @Test
    void trendClampsDaysToBounds() {
        service.trend("tenant-1", 0);
        service.trend("tenant-1", 500);

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(usageMapper, times(2)).findByTenantIdAndDateRange(eq("tenant-1"), from.capture(), to.capture());

        assertThat(from.getAllValues().get(0)).isEqualTo(LocalDate.now());
        assertThat(to.getAllValues().get(0)).isEqualTo(LocalDate.now());
        assertThat(from.getAllValues().get(1)).isEqualTo(LocalDate.now().minusDays(89));
        assertThat(to.getAllValues().get(1)).isEqualTo(LocalDate.now());
    }

    @Test
    void recordUsageWritesTenantAndUserRowsWhenUserPresent() {
        service.recordUsage("tenant-1", "alice", "balanced", 100, 50, "chat");

        verify(usageMapper).addUsage(eq("tenant-1"), any(), eq(1L), eq(100L), eq(50L), any());
        ArgumentCaptor<String> userKey = ArgumentCaptor.forClass(String.class);
        verify(userUsageMapper).addUsage(eq("tenant-1"), userKey.capture(), any(),
                eq(1L), eq(100L), eq(50L), any());
        assertThat(userKey.getValue()).isEqualTo("alice");
    }

    @Test
    void recordUsageSkipsUserRowWhenUserKeyBlank() {
        // 后台无身份线程（深度研究/图谱抽取）只记租户行，不编造用户归属
        service.recordUsage("tenant-1", null, "balanced", 100, 50, "research");
        service.recordUsage("tenant-1", "  ", "balanced", 100, 50, "research");

        verify(usageMapper, times(2)).addUsage(eq("tenant-1"), any(), eq(1L), eq(100L), eq(50L), any());
        verify(userUsageMapper, org.mockito.Mockito.never())
                .addUsage(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void userUsageAggregatesAndEnrichesDisplayName() {
        when(userUsageMapper.sumByUser(eq("tenant-1"), any(), any())).thenReturn(List.of(
                TenantUserUsageDaily.builder()
                        .userId("alice")
                        .requestCount(8L)
                        .inputTokens(900L)
                        .outputTokens(300L)
                        .totalCostUsd(new BigDecimal("0.1234"))
                        .build(),
                TenantUserUsageDaily.builder()
                        .userId("api-key:ops")
                        .requestCount(2L)
                        .inputTokens(100L)
                        .outputTokens(50L)
                        .totalCostUsd(new BigDecimal("0.0100"))
                        .build()));
        when(userAccountMapper.findByUsername("alice"))
                .thenReturn(com.enterprise.iqk.domain.UserAccount.builder()
                        .username("alice").displayName("Alice 张").build());
        when(userAccountMapper.findByUsername("api-key:ops")).thenReturn(null);

        var result = service.userUsage("tenant-1", 14);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getUserId()).isEqualTo("alice");
        assertThat(result.get(0).getDisplayName()).isEqualTo("Alice 张");
        assertThat(result.get(0).getCostUsd()).isEqualByComparingTo("0.1234");
        // 未匹配 users 表的键（如 API Key 主体）保留原键、显示名为空
        assertThat(result.get(1).getUserId()).isEqualTo("api-key:ops");
        assertThat(result.get(1).getDisplayName()).isNull();
    }
}
