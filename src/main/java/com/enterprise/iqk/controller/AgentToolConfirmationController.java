package com.enterprise.iqk.controller;

import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.service.platform.PlatformToolConfirmationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 平台工具人工确认入口（R7.3）：聊天内挂起的高危工具调用由用户审批/取消。
 * 权限与会话写侧一致（PERM_CHAT_WRITE，SecurityConfiguration）；租户从 MDC 取，
 * 服务层再按 tenant_id 圈定——跨租户 ID 不可见（404）。
 */
@RestController
@RequestMapping("/ai/agent-tool-confirmations")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('PERM_CHAT_WRITE','ROLE_ADMIN')")
public class AgentToolConfirmationController {

    private final PlatformToolConfirmationService confirmationService;

    /** 审批并执行：CAS 抢占 PENDING，执行结果/失败状态随响应返回。 */
    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable Long id) {
        return confirmationService.approve(id, currentTenantId());
    }

    /** 取消：PENDING → CANCELLED。 */
    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id) {
        return confirmationService.cancel(id, currentTenantId());
    }

    private String currentTenantId() {
        return TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
    }
}
