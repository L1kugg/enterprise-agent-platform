package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.vo.AgentCatalogItemVO;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.service.agent.AgentCatalogService;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 智能体目录入口（GET /ai/agents）：工作台选择器数据源，权限与聊天写侧一致（SecurityConfiguration）。 */
@RestController
@RequestMapping("/ai/agents")
@RequiredArgsConstructor
public class AgentCatalogController {

    private final AgentCatalogService agentCatalogService;

    /** 当前租户可用的已发布智能体目录（仅展示字段）。 */
    @GetMapping
    public List<AgentCatalogItemVO> list() {
        return agentCatalogService.listUsable(
                TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE)));
    }
}
