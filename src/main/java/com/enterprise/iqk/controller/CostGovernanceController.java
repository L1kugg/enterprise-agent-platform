package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.vo.TenantBudgetUpdateVO;
import com.enterprise.iqk.domain.vo.TenantCostSummaryVO;
import com.enterprise.iqk.domain.vo.TenantCostTrendVO;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.service.TenantCostService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/cost")
@RequiredArgsConstructor
public class CostGovernanceController {
    private final TenantCostService tenantCostService;

    @GetMapping("/summary")
    public TenantCostSummaryVO summary() {
        return tenantCostService.summary(TenantContext.currentTenantId());
    }

    /** 近 N 天逐日用量趋势（裸数组，缺天补零；days 非数字由 Spring 直接 400）。 */
    @GetMapping("/trend")
    public List<TenantCostTrendVO> trend(@RequestParam(name = "days", defaultValue = "30") int days) {
        return tenantCostService.trend(TenantContext.currentTenantId(), days);
    }

    @PostMapping("/budget")
    public TenantCostSummaryVO updateBudget(@RequestBody TenantBudgetUpdateVO request) {
        if (request != null) {
            request.setTenantId(TenantContext.currentTenantId());
        }
        return tenantCostService.updateBudget(request);
    }
}
