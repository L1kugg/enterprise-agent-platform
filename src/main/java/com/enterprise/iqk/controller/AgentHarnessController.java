package com.enterprise.iqk.controller;

import com.enterprise.iqk.agent.harness.ActionSchema;
import com.enterprise.iqk.agent.harness.ActionSchemaRegistry;
import com.enterprise.iqk.agent.harness.AgentObservation;
import com.enterprise.iqk.agent.harness.TrustedActionPreviewResponse;
import com.enterprise.iqk.agent.harness.TrustedActionRequest;
import com.enterprise.iqk.agent.harness.TrustedActionService;
import com.enterprise.iqk.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Agent Harness", description = "Trusted agent runtime action confirmation")
@RestController
@RequestMapping("/ai/harness")
@RequiredArgsConstructor
/**
 * 受信动作的 HTTP 入口：列出动作 schema、preview 预览并签发一次性令牌、execute 凭令牌执行。
 * 全部端点要求 PERM_AGENT_TRUSTED 权限或 ADMIN 角色；租户一律取自 TenantContext，不信任请求体传入。
 */
public class AgentHarnessController {
    private final TrustedActionService trustedActionService;
    private final ActionSchemaRegistry actionSchemaRegistry;

    @Operation(summary = "List registered agent action schemas")
    @GetMapping("/actions")
    @PreAuthorize("hasAnyAuthority('PERM_AGENT_TRUSTED','ROLE_ADMIN')")
    /** 列出已注册的 agent 动作 schema。 */
    public ResponseEntity<List<ActionSchema>> actions() {
        return ResponseEntity.ok(actionSchemaRegistry.list());
    }

    @Operation(summary = "Preview a trusted runtime action and create a one-time confirmation token")
    @PostMapping("/actions/preview")
    @PreAuthorize("hasAnyAuthority('PERM_AGENT_TRUSTED','ROLE_ADMIN')")
    /** 预览受信动作并签发一次性确认令牌；租户以服务端上下文覆写请求体字段。 */
    public ResponseEntity<TrustedActionPreviewResponse> preview(@RequestBody TrustedActionRequest request) {
        return ResponseEntity.ok(trustedActionService.preview(
                request.withTenantId(TenantContext.currentTenantId())));
    }

    @Operation(summary = "Execute a previously previewed trusted runtime action")
    @PostMapping("/actions/execute/{token}")
    @PreAuthorize("hasAnyAuthority('PERM_AGENT_TRUSTED','ROLE_ADMIN')")
    /** 凭一次性令牌执行此前预览的受信动作，返回观测结果。 */
    public ResponseEntity<?> execute(@PathVariable String token) {
        AgentObservation observation = trustedActionService.execute(token, TenantContext.currentTenantId());
        return ResponseEntity.ok(observation.toMap());
    }
}
