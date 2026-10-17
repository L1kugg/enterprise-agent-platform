package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.vo.KnowledgeBaseCatalogItemVO;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.service.kb.KnowledgeBaseCatalogService;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户侧知识库目录（GET /ai/knowledge-bases，FR-R5-01）：上传/试搜选择器的数据源。
 * 只透出展示字段与真实文档统计；权限在 SecurityConfiguration 与知识库读侧一致。
 */
@RestController
@RequestMapping("/ai/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseCatalogController {

    private final KnowledgeBaseCatalogService knowledgeBaseCatalogService;

    /** 当前租户可用的（ACTIVE）知识库目录，附真实文档统计。 */
    @GetMapping
    public List<KnowledgeBaseCatalogItemVO> list() {
        return knowledgeBaseCatalogService.listUsable(
                TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE)));
    }
}
