package com.enterprise.iqk.controller;

import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.service.agent.PlatformAgentStatsService;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 智能体观测看板端点（R2.2 读侧，FR-R2.2）：汇总统计/按天趋势/调用日志分页，
 * 数据全部来自 agent_invocation_log 真实聚合。仅管理员（config_json 同界）。
 */
@RestController
@RequestMapping("/platform/agents")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PlatformAgentStatsController {

    private final PlatformAgentStatsService statsService;

    /** 汇总统计 + 常见错误 Top5（days 缺省 7，钳制 [1,90]）。 */
    @GetMapping("/{id}/stats")
    public Map<String, Object> stats(@PathVariable Long id,
                                     @RequestParam(defaultValue = "7") int days) {
        return statsService.stats(currentTenantId(), id, days);
    }

    /** 按天趋势（缺天补零，曲线连续）。 */
    @GetMapping("/{id}/trend")
    public List<Map<String, Object>> trend(@PathVariable Long id,
                                           @RequestParam(defaultValue = "7") int days) {
        return statsService.trend(currentTenantId(), id, days);
    }

    /** 调用日志分页（status=SUCCESS|BLOCKED|ERROR，channel=platform|debug 可选过滤）。 */
    @GetMapping("/{id}/logs")
    public Map<String, Object> logs(@PathVariable Long id,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int pageSize,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String channel) {
        return statsService.logs(currentTenantId(), id, page, pageSize, status, channel);
    }

    private String currentTenantId() {
        return TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
    }
}
