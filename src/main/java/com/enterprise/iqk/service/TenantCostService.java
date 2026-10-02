package com.enterprise.iqk.service;

import com.enterprise.iqk.config.properties.CostGovernanceProperties;
import com.enterprise.iqk.domain.TenantBudget;
import com.enterprise.iqk.domain.TenantUsageDaily;
import com.enterprise.iqk.domain.vo.TenantBudgetUpdateVO;
import com.enterprise.iqk.domain.vo.TenantCostSummaryVO;
import com.enterprise.iqk.domain.vo.TenantCostTrendVO;
import com.enterprise.iqk.mapper.TenantBudgetMapper;
import com.enterprise.iqk.mapper.TenantUsageDailyMapper;
import com.enterprise.iqk.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
/**
 * 租户成本记账三件套：estimateTokens 粗估 → assertBudget 预算闸门 → recordUsage 落账。
 * 费用按成本档位对应的 USD/1k tokens 单价折算，日用量累加到 tenant_usage_daily；
 * 成本治理开关关闭时所有入口直接放行，不做任何记账。
 */
public class TenantCostService {
    private final TenantBudgetMapper tenantBudgetMapper;
    private final TenantUsageDailyMapper tenantUsageDailyMapper;
    private final CostGovernanceProperties costGovernanceProperties;

    /** 请求前预算闸门：本月已花费 + 预估费用超过月度预算且租户开启硬限制时，直接拒绝请求。 */
    public void assertBudget(String tenantId, String costTier, long inputTokens, long outputTokens) {
        if (!costGovernanceProperties.isEnabled()) {
            return;
        }
        String tenant = TenantContext.normalize(tenantId);
        TenantBudget budget = ensureBudget(tenant);
        BigDecimal estimatedCost = calculateCost(costTier, inputTokens + outputTokens);
        BigDecimal monthCost = monthCost(tenant, YearMonth.now());
        BigDecimal projected = monthCost.add(estimatedCost);
        Integer hardLimitVal = budget.getHardLimitEnabled();
        boolean hardLimit = Integer.valueOf(1).equals(hardLimitVal);
        if (hardLimit && projected.compareTo(defaultDecimal(budget.getMonthlyBudgetUsd())) > 0) {
            throw new IllegalArgumentException("tenant budget exceeded, request blocked");
        }
    }

    /** 请求后落账：按档位单价折算费用，把请求数/token 数/费用累加进当日用量行。 */
    public void recordUsage(String tenantId,
                            String costTier,
                            long inputTokens,
                            long outputTokens,
                            String endpointTag) {
        if (!costGovernanceProperties.isEnabled()) {
            return;
        }
        String tenant = TenantContext.normalize(tenantId);
        long safeInput = Math.max(0, inputTokens);
        long safeOutput = Math.max(0, outputTokens);
        BigDecimal cost = calculateCost(costTier, safeInput + safeOutput);
        tenantUsageDailyMapper.addUsage(
                tenant,
                LocalDate.now(),
                1,
                safeInput,
                safeOutput,
                cost
        );
    }

    /** 汇总租户本月/今日的请求量、token 与费用，并给出预算余量与是否超限。 */
    public TenantCostSummaryVO summary(String tenantId) {
        String tenant = TenantContext.normalize(tenantId);
        TenantBudget budget = ensureBudget(tenant);
        YearMonth now = YearMonth.now();
        LocalDate monthStart = now.atDay(1);
        LocalDate monthEnd = now.atEndOfMonth();
        LocalDate today = LocalDate.now();

        Long monthRequestCount = safeLong(tenantUsageDailyMapper.sumRequestCount(tenant, monthStart, monthEnd));
        Long monthInput = safeLong(tenantUsageDailyMapper.sumInputTokens(tenant, monthStart, monthEnd));
        Long monthOutput = safeLong(tenantUsageDailyMapper.sumOutputTokens(tenant, monthStart, monthEnd));
        BigDecimal monthCost = defaultDecimal(tenantUsageDailyMapper.sumCostUsd(tenant, monthStart, monthEnd));

        Long todayRequestCount = safeLong(tenantUsageDailyMapper.sumRequestCount(tenant, today, today));
        BigDecimal todayCost = defaultDecimal(tenantUsageDailyMapper.sumCostUsd(tenant, today, today));

        BigDecimal budgetLimit = defaultDecimal(budget.getMonthlyBudgetUsd());
        BigDecimal remaining = budgetLimit.subtract(monthCost).max(BigDecimal.ZERO);
        boolean exceeded = monthCost.compareTo(budgetLimit) > 0;

        return TenantCostSummaryVO.builder()
                .tenantId(tenant)
                .month(now.toString())
                .monthlyBudgetUsd(budgetLimit)
                .hardLimitEnabled(Integer.valueOf(1).equals(budget.getHardLimitEnabled()))
                .monthCostUsd(scale(monthCost))
                .monthRequestCount(monthRequestCount)
                .monthInputTokens(monthInput)
                .monthOutputTokens(monthOutput)
                .todayCostUsd(scale(todayCost))
                .todayRequestCount(todayRequestCount)
                .budgetRemainingUsd(scale(remaining))
                .budgetExceeded(exceeded)
                .build();
    }

    /** 近 N 天逐日用量趋势（含今日），缺数据的天补零，保证连续序列。 */
    public List<TenantCostTrendVO> trend(String tenantId, int days) {
        String tenant = TenantContext.normalize(tenantId);
        int safeDays = Math.min(90, Math.max(1, days));
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(safeDays - 1L);
        List<TenantUsageDaily> rows = tenantUsageDailyMapper.findByTenantIdAndDateRange(tenant, from, today);
        Map<LocalDate, TenantUsageDaily> byDate = rows.stream()
                .collect(Collectors.toMap(TenantUsageDaily::getUsageDate, Function.identity(), (a, b) -> a));
        List<TenantCostTrendVO> result = new ArrayList<>(safeDays);
        for (int i = 0; i < safeDays; i++) {
            LocalDate day = from.plusDays(i);
            TenantUsageDaily row = byDate.get(day);
            result.add(TenantCostTrendVO.builder()
                    .date(day.toString())
                    .requestCount(safeLong(row == null ? null : row.getRequestCount()))
                    .inputTokens(safeLong(row == null ? null : row.getInputTokens()))
                    .outputTokens(safeLong(row == null ? null : row.getOutputTokens()))
                    .costUsd(scale(row == null ? null : row.getTotalCostUsd()))
                    .build());
        }
        return result;
    }

    /** 更新租户月度预算与硬限制开关（负数预算拒绝）；无预算记录时先按默认值初始化。 */
    public TenantCostSummaryVO updateBudget(TenantBudgetUpdateVO request) {
        if (request == null) {
            throw new IllegalArgumentException("budget payload is required");
        }
        String tenant = TenantContext.normalize(request.getTenantId());
        TenantBudget budget = ensureBudget(tenant);
        if (request.getMonthlyBudgetUsd() != null) {
            if (request.getMonthlyBudgetUsd().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("monthlyBudgetUsd must be non-negative");
            }
            budget.setMonthlyBudgetUsd(request.getMonthlyBudgetUsd());
        }
        if (request.getHardLimitEnabled() != null) {
            budget.setHardLimitEnabled(Boolean.TRUE.equals(request.getHardLimitEnabled()) ? 1 : 0);
        }
        budget.setUpdatedAt(LocalDateTime.now());
        tenantBudgetMapper.updateById(budget);
        return summary(tenant);
    }

    /** 按码点数除以估算系数粗估 token 数：空文本返回 0，非空文本至少记 1。 */
    public long estimateTokens(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        int divisor = Math.max(1, costGovernanceProperties.getTokenEstimateDivisor());
        int length = text.codePointCount(0, text.length());
        return Math.max(1L, (length + divisor - 1L) / divisor);
    }

    /** 读取租户预算记录，不存在时按默认配置初始化一条。 */
    private TenantBudget ensureBudget(String tenantId) {
        TenantBudget existing = tenantBudgetMapper.findByTenantId(tenantId);
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        TenantBudget inserted = TenantBudget.builder()
                .tenantId(tenantId)
                .monthlyBudgetUsd(defaultDecimal(costGovernanceProperties.getDefaultMonthlyBudgetUsd()))
                .hardLimitEnabled(costGovernanceProperties.isDefaultHardLimitEnabled() ? 1 : 0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        tenantBudgetMapper.insert(inserted);
        return tenantBudgetMapper.findByTenantId(tenantId);
    }

    /** 汇总指定月份的费用，空值归零。 */
    private BigDecimal monthCost(String tenantId, YearMonth month) {
        BigDecimal total = tenantUsageDailyMapper.sumCostUsd(tenantId, month.atDay(1), month.atEndOfMonth());
        return defaultDecimal(total);
    }

    /** 按档位单价折算费用（保留 6 位小数），未知档位回落 medium。 */
    private BigDecimal calculateCost(String costTier, long totalTokens) {
        String tier = StringUtils.hasText(costTier) ? costTier.trim().toLowerCase(Locale.ROOT) : "medium";
        BigDecimal unit = costGovernanceProperties.getUsdPer1kTokens().getOrDefault(
                tier,
                costGovernanceProperties.getUsdPer1kTokens().getOrDefault("medium", new BigDecimal("0.0030"))
        );
        BigDecimal tokens = BigDecimal.valueOf(Math.max(0, totalTokens));
        return tokens.multiply(defaultDecimal(unit))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
    }

    /** Long 空值归 0。 */
    private Long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    /** BigDecimal 空值归零。 */
    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 统一保留 4 位小数（HALF_UP）。 */
    private BigDecimal scale(BigDecimal value) {
        return defaultDecimal(value).setScale(4, RoundingMode.HALF_UP);
    }
}
