package com.enterprise.iqk.controller;

import com.enterprise.iqk.config.SecurityConfiguration;
import com.enterprise.iqk.config.properties.SecurityProperties;
import com.enterprise.iqk.ingestion.IngestionService;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import com.enterprise.iqk.security.AuditLogFilter;
import com.enterprise.iqk.security.HttpMetricsFilter;
import com.enterprise.iqk.security.RateLimitFilter;
import com.enterprise.iqk.security.RequestContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 方法级 @PreAuthorize 生效链路验证：@WebMvcTest 切片默认不装安全配置，
 * 这里显式 @Import(SecurityConfiguration.class) 激活方法安全，用 ROLE_USER
 * 断言 /admin/** 一律 403（URL matcher 与方法注解双保险中至少一条拦住）。
 */
@WebMvcTest(AdminController.class)
@Import(SecurityConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminControllerSecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SecurityProperties securityProperties;
    @MockBean
    private RequestContextFilter requestContextFilter;
    @MockBean
    private com.enterprise.iqk.security.ApiKeyOrJwtAuthFilter apiKeyOrJwtAuthFilter;
    @MockBean
    private RateLimitFilter rateLimitFilter;
    @MockBean
    private AuditLogFilter auditLogFilter;
    @MockBean
    private HttpMetricsFilter httpMetricsFilter;
    @MockBean
    private IngestionJobMapper ingestionJobMapper;
    @MockBean
    private IngestionService ingestionService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private RequestPostProcessor asUser() {
        return request -> {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "user", "jwt", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            return request;
        };
    }

    @Test
    void shouldRejectUserForList() throws Exception {
        mockMvc.perform(get("/admin/documents").with(asUser()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.msg").value("权限不足"));
    }

    @Test
    void shouldRejectUserForDelete() throws Exception {
        mockMvc.perform(delete("/admin/documents/u-alice/doc-1").with(asUser()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.msg").value("权限不足"));

        verifyNoInteractions(ingestionService);
    }
}
