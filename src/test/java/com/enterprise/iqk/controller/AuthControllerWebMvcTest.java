package com.enterprise.iqk.controller;

import com.enterprise.iqk.config.properties.SecurityProperties;
import com.enterprise.iqk.domain.UserAccount;
import com.enterprise.iqk.security.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = AuthController.class, excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ApiKeyOrJwtAuthFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RateLimitFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AuditLogFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = HttpMetricsFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RequestContextFilter.class)
})
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerWebMvcTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApiKeyAuthService apiKeyAuthService;
    @MockBean
    private ApiKeyLifecycleService apiKeyLifecycleService;
    @MockBean
    private JwtService jwtService;
    @MockBean
    private PermissionService permissionService;
    @MockBean
    private SecurityProperties securityProperties;
    @MockBean
    private UserAuthService userAuthService;
    @MockBean
    private UserSessionService userSessionService;

    @Test
    void tokenEndpointIssuesJwtWithoutRefreshToken() throws Exception {
        when(apiKeyAuthService.authenticate("abc")).thenReturn(AuthIdentity.builder()
                .principal("tester")
                .roles(List.of("ADMIN"))
                .permissions(List.of("chat:write"))
                .source("api_key")
                .tenantId("tenant-a")
                .build());
        when(permissionService.permissionsForRoles(any())).thenReturn(List.of("chat:write"));
        when(jwtService.issueToken(any(), any(), any(), eq("tenant-a"))).thenReturn("jwt-token");
        when(securityProperties.getJwtExpireMinutes()).thenReturn(120);

        mockMvc.perform(post("/auth/token").header("X-API-Key", "abc").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.token").value("jwt-token"))
                // 程序化通道只发短周期 JWT，刷新令牌机制已随 Session 化下线
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void shouldRotateApiKey() throws Exception {
        when(apiKeyLifecycleService.rotate("legacy", "manual", "public"))
                .thenReturn(new ApiKeyLifecycleService.ApiKeyIssueResult("new-raw", "legacy-v2", "public", LocalDateTime.now().plusDays(30)));

        mockMvc.perform(post("/auth/api-keys/rotate")
                        .param("keyName", "legacy")
                        .param("reason", "manual")
                        .param("tenantId", "public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.rawApiKey").value("new-raw"))
                .andExpect(jsonPath("$.tenantId").value("public"));
    }

    @Test
    void registerCreatesSessionCookieAndReturnsNoToken() throws Exception {
        UserAccount alice = UserAccount.builder()
                .id(1L)
                .username("alice")
                .tenantId("public")
                .enabled(1)
                .build();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(30);
        when(userAuthService.register("alice", "password123")).thenReturn(alice);
        when(userAuthService.roleNamesOf(alice)).thenReturn(List.of("USER"));
        when(userSessionService.create(eq("alice"), any(), eq("public")))
                .thenReturn(new UserSessionService.SessionIssue("raw-alice", expiresAt));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.tenantId").value("public"))
                .andExpect(jsonPath("$.role").value("USER"))
                // 凭据只在 HttpOnly Cookie 里，响应体不出现任何令牌
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
        verify(userSessionService).writeSessionCookie(any(HttpServletResponse.class), eq("raw-alice"), eq(expiresAt));
    }

    @Test
    void shouldRejectDuplicateRegistration() throws Exception {
        when(userAuthService.register("alice", "password123"))
                .thenThrow(new IllegalArgumentException("用户名已被占用"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("用户名已被占用"));
    }

    @Test
    void shouldRejectRegisterWithMissingFields() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("请输入用户名和密码"));
    }

    @Test
    void shouldRejectLoginWithWrongPassword() throws Exception {
        when(userAuthService.verify("alice", "wrong-pass")).thenReturn(null);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("用户名或密码不正确"));
        verify(userSessionService, never()).create(anyString(), any(), anyString());
    }

    @Test
    void loginCreatesSessionCookie() throws Exception {
        UserAccount bob = UserAccount.builder()
                .id(2L)
                .username("bob")
                .tenantId("public")
                .enabled(1)
                .build();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(30);
        when(userAuthService.verify("bob", "password123")).thenReturn(bob);
        when(userAuthService.roleNamesOf(bob)).thenReturn(List.of("ADMIN"));
        when(userSessionService.create(eq("bob"), any(), eq("public")))
                .thenReturn(new UserSessionService.SessionIssue("raw-bob", expiresAt));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.token").doesNotExist());
        verify(userSessionService).writeSessionCookie(any(HttpServletResponse.class), eq("raw-bob"), eq(expiresAt));
    }

    @Test
    void meReturnsIdentityForActiveSession() throws Exception {
        when(userSessionService.rawSessionIdOf(any(HttpServletRequest.class))).thenReturn("raw-bob");
        when(userSessionService.resolve("raw-bob")).thenReturn(AuthIdentity.builder()
                .principal("bob")
                .roles(List.of("ADMIN"))
                .permissions(List.of())
                .source("session")
                .tenantId("public")
                .build());

        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.tenantId").value("public"));
    }

    @Test
    void meReturnsOkZeroWithoutSession() throws Exception {
        when(userSessionService.rawSessionIdOf(any(HttpServletRequest.class))).thenReturn("");
        when(userSessionService.resolve("")).thenReturn(null);

        // 未登录不是错误：HTTP 200 + ok=0，前端据此回到登录页
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(0));
    }

    @Test
    void logoutRevokesSessionAndClearsCookie() throws Exception {
        when(userSessionService.rawSessionIdOf(any(HttpServletRequest.class))).thenReturn("raw-bob");
        when(userSessionService.revoke("raw-bob")).thenReturn(true);

        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1));
        verify(userSessionService).revoke("raw-bob");
        verify(userSessionService).clearSessionCookie(any(HttpServletResponse.class));
    }

    @Test
    void logoutWithoutSessionStillClearsCookie() throws Exception {
        when(userSessionService.rawSessionIdOf(any(HttpServletRequest.class))).thenReturn("");
        when(userSessionService.revoke("")).thenReturn(false);

        // 幂等：未登录调用也返回 ok=1 并清 Cookie
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1));
        verify(userSessionService).clearSessionCookie(any(HttpServletResponse.class));
    }
}
