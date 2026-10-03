package com.enterprise.iqk.controller;

import com.enterprise.iqk.config.properties.SecurityProperties;
import com.enterprise.iqk.domain.UserAccount;
import com.enterprise.iqk.security.*;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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
    private RefreshTokenService refreshTokenService;
    @MockBean
    private PermissionService permissionService;
    @MockBean
    private SecurityProperties securityProperties;
    @MockBean
    private UserAuthService userAuthService;

    @Test
    void shouldIssueAccessAndRefreshTokens() throws Exception {
        when(apiKeyAuthService.authenticate("abc")).thenReturn(AuthIdentity.builder()
                .principal("tester")
                .roles(List.of("ADMIN"))
                .permissions(List.of("chat:write"))
                .source("api_key")
                .tenantId("tenant-a")
                .build());
        when(permissionService.permissionsForRoles(any())).thenReturn(List.of("chat:write"));
        when(jwtService.issueToken(any(), any(), any(), eq("tenant-a"))).thenReturn("jwt-token");
        when(refreshTokenService.issue(any(), any(), eq("tenant-a")))
                .thenReturn(new RefreshTokenService.RefreshTokenIssueResult("refresh-token", "tenant-a", LocalDateTime.now().plusDays(7)));
        when(securityProperties.getJwtExpireMinutes()).thenReturn(120);

        mockMvc.perform(post("/auth/token").header("X-API-Key", "abc").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
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
    void shouldRegisterAndIssueSession() throws Exception {
        UserAccount alice = UserAccount.builder()
                .id(1L)
                .username("alice")
                .tenantId("u-alice")
                .enabled(1)
                .build();
        when(userAuthService.register("alice", "password123")).thenReturn(alice);
        when(userAuthService.roleNamesOf(alice)).thenReturn(List.of("USER"));
        when(permissionService.permissionsForRoles(any())).thenReturn(List.of("chat:write", "eval:read"));
        when(jwtService.issueToken(eq("alice"), any(), any(), eq("u-alice"))).thenReturn("jwt-alice");
        when(refreshTokenService.issue(eq("alice"), any(), eq("u-alice")))
                .thenReturn(new RefreshTokenService.RefreshTokenIssueResult("refresh-alice", "u-alice", LocalDateTime.now().plusDays(7)));
        when(securityProperties.getJwtExpireMinutes()).thenReturn(120);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.token").value("jwt-alice"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-alice"))
                .andExpect(jsonPath("$.tenantId").value("u-alice"))
                .andExpect(jsonPath("$.role").value("USER"));
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
    }

    @Test
    void shouldLoginAndIssueSession() throws Exception {
        UserAccount bob = UserAccount.builder()
                .id(2L)
                .username("bob")
                .tenantId("u-bob")
                .enabled(1)
                .build();
        when(userAuthService.verify("bob", "password123")).thenReturn(bob);
        when(userAuthService.roleNamesOf(bob)).thenReturn(List.of("USER"));
        when(permissionService.permissionsForRoles(any())).thenReturn(List.of("chat:write"));
        when(jwtService.issueToken(eq("bob"), any(), any(), eq("u-bob"))).thenReturn("jwt-bob");
        when(refreshTokenService.issue(eq("bob"), any(), eq("u-bob")))
                .thenReturn(new RefreshTokenService.RefreshTokenIssueResult("refresh-bob", "u-bob", LocalDateTime.now().plusDays(7)));
        when(securityProperties.getJwtExpireMinutes()).thenReturn(120);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.token").value("jwt-bob"))
                .andExpect(jsonPath("$.tenantId").value("u-bob"))
                .andExpect(jsonPath("$.role").value("USER"));
    }
}
