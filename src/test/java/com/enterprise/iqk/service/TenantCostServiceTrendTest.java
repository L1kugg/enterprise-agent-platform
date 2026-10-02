package com.enterprise.iqk.service;

import com.enterprise.iqk.config.properties.CostGovernanceProperties;
import com.enterprise.iqk.domain.TenantUsageDaily;
import com.enterprise.iqk.domain.vo.TenantCostTrendVO;
import com.enterprise.iqk.mapper.TenantBudgetMapper;
import com.enterprise.iqk.mapper.TenantUsageDailyMapper;
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
    private TenantCostService service;

    @BeforeEach
    void setUp() {
        usageMapper = mock(TenantUsageDailyMapper.class);
        budgetMapper = mock(TenantBudgetMapper.class);
        service = new TenantCostService(budgetMapper, usageMapper, new CostGovernanceProperties());
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
}
