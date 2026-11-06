package com.enterprise.iqk.security;

import com.enterprise.iqk.config.properties.SecurityProperties;
import com.enterprise.iqk.mapper.UserAccountMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyOrJwtAuthFilterTest {

    private static SecurityProperties enabledProps() {
        SecurityProperties props = new SecurityProperties();
        props.setEnabled(true);
        return props;
    }

    @Test
    void shouldReturn401WithoutCredentials() throws ServletException, IOException {
        ApiKeyOrJwtAuthFilter filter = new ApiKeyOrJwtAuthFilter(enabledProps(), mock(ApiKeyAuthService.class),
                mock(JwtService.class), mock(UserAccountMapper.class), mock(UserSessionService.class));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ai/chat");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        assertEquals(401, resp.getStatus());
        // 手写 401 JSON 须按 UTF-8 编码，中文 msg 不能退化成问号
        assertEquals("{\"ok\":0,\"msg\":\"未登录或登录已过期，请重新登录\"}", resp.getContentAsString());
    }

    @Test
    void sessionCookieIdentityTakesPriorityOverOtherChannels() throws ServletException, IOException {
        ApiKeyAuthService apiKeyAuthService = mock(ApiKeyAuthService.class);
        JwtService jwtService = mock(JwtService.class);
        UserSessionService sessionService = mock(UserSessionService.class);
        when(sessionService.rawSessionIdOf(any())).thenReturn("raw-session");
        when(sessionService.resolve("raw-session")).thenReturn(AuthIdentity.builder()
                .principal("alice")
                .roles(List.of("USER"))
                .permissions(List.of("chat:write"))
                .tenantId("public")
                .source("session")
                .build());
        ApiKeyOrJwtAuthFilter filter = new ApiKeyOrJwtAuthFilter(enabledProps(), apiKeyAuthService,
                jwtService, mock(UserAccountMapper.class), sessionService);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ai/chat");
        req.addHeader("X-API-Key", "should-not-be-consulted");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, (request, response) -> {
            assertEquals("public", request.getAttribute(TenantContext.TENANT_REQUEST_ATTRIBUTE));
            // 会话身份与其余通道产出同一套 ROLE_/PERM_ 权限
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertEquals("alice", auth.getName());
            assertEquals(true, auth.getAuthorities().stream()
                    .anyMatch(a -> "PERM_CHAT_WRITE".equals(a.getAuthority())));
        });
        assertEquals(200, resp.getStatus());
        verify(apiKeyAuthService, never()).authenticate(any());
    }

    @Test
    void shouldPassWithApiKey() throws ServletException, IOException {
        ApiKeyAuthService apiKeyAuthService = mock(ApiKeyAuthService.class);
        when(apiKeyAuthService.authenticate("ak")).thenReturn(AuthIdentity.builder()
                .principal("tester")
                .roles(List.of("ADMIN"))
                .permissions(List.of("chat:write"))
                .tenantId("tenant-a")
                .source("api_key")
                .build());
        ApiKeyOrJwtAuthFilter filter = new ApiKeyOrJwtAuthFilter(enabledProps(), apiKeyAuthService,
                mock(JwtService.class), mock(UserAccountMapper.class), mock(UserSessionService.class));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ai/chat");
        req.addHeader("X-API-Key", "ak");
        req.addHeader(TenantContext.TENANT_HEADER, "tenant-b");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, (request, response) -> {
            assertEquals("tenant-a", request.getAttribute(TenantContext.TENANT_REQUEST_ATTRIBUTE));
            assertEquals("tenant-a", MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
        });
        assertEquals(200, resp.getStatus());
        assertEquals("tenant-a", resp.getHeader(TenantContext.TENANT_HEADER));
    }

    @Test
    void shouldPassPublicAuthEndpointsWithoutCredentials() throws ServletException, IOException {
        ApiKeyOrJwtAuthFilter filter = new ApiKeyOrJwtAuthFilter(enabledProps(), mock(ApiKeyAuthService.class),
                mock(JwtService.class), mock(UserAccountMapper.class), mock(UserSessionService.class));

        for (String path : List.of("/auth/register", "/auth/login", "/auth/me", "/auth/logout")) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(req, resp, new MockFilterChain());
            assertEquals(200, resp.getStatus(), path + " should be public");
        }
    }
}
