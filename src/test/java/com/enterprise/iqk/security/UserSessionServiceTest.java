package com.enterprise.iqk.security;

import com.enterprise.iqk.config.properties.SecurityProperties;
import com.enterprise.iqk.domain.UserAccount;
import com.enterprise.iqk.domain.UserSessionRecord;
import com.enterprise.iqk.mapper.UserAccountMapper;
import com.enterprise.iqk.mapper.UserSessionMapper;
import com.enterprise.iqk.util.HashUtils;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 服务端会话：只落哈希、解析时回查账号 enabled（封禁即时生效）、吊销幂等、
 * Cookie 为 HttpOnly + SameSite=Strict 且 Secure 按配置。
 */
class UserSessionServiceTest {

    private SecurityProperties properties;
    private UserSessionMapper sessionMapper;
    private UserAccountMapper accountMapper;
    private PermissionService permissionService;
    private UserSessionService service;

    @BeforeEach
    void setUp() {
        properties = new SecurityProperties();
        sessionMapper = mock(UserSessionMapper.class);
        accountMapper = mock(UserAccountMapper.class);
        permissionService = mock(PermissionService.class);
        service = new UserSessionService(properties, sessionMapper, accountMapper, permissionService);
    }

    private UserSessionRecord activeSession(String raw) {
        return UserSessionRecord.builder()
                .id(9L)
                .sessionHash(HashUtils.sha256Hex(raw))
                .principal("alice")
                .tenantId("public")
                .rolesCsv("ADMIN,USER")
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(30))
                .build();
    }

    @Test
    void createStoresOnlyHashAndReturnsRawOnce() {
        UserSessionService.SessionIssue issue = service.create("alice", List.of("ADMIN", "USER"), "public");

        ArgumentCaptor<UserSessionRecord> stored = ArgumentCaptor.forClass(UserSessionRecord.class);
        verify(sessionMapper).insert(stored.capture());
        assertThat(issue.rawSessionId()).isNotBlank();
        // 库里只有哈希：泄库拿不到可用会话
        assertThat(stored.getValue().getSessionHash())
                .isEqualTo(HashUtils.sha256Hex(issue.rawSessionId()))
                .isNotEqualTo(issue.rawSessionId());
        assertThat(stored.getValue().getRolesCsv()).isEqualTo("ADMIN,USER");
        assertThat(stored.getValue().getExpiresAt()).isAfter(LocalDateTime.now().plusDays(29));
    }

    @Test
    void resolveActiveSessionOfEnabledAccount() {
        when(sessionMapper.findActiveByHash(HashUtils.sha256Hex("raw-1"))).thenReturn(activeSession("raw-1"));
        when(accountMapper.findByUsername("alice")).thenReturn(UserAccount.builder().username("alice").enabled(1).build());
        when(permissionService.permissionsForRoles(List.of("ADMIN", "USER"))).thenReturn(List.of("chat:write"));

        AuthIdentity identity = service.resolve("raw-1");

        assertThat(identity).isNotNull();
        assertThat(identity.getPrincipal()).isEqualTo("alice");
        assertThat(identity.getRoles()).containsExactly("ADMIN", "USER");
        assertThat(identity.getPermissions()).containsExactly("chat:write");
        assertThat(identity.getSource()).isEqualTo("session");
        assertThat(identity.getTenantId()).isEqualTo("public");
    }

    @Test
    void resolveRejectsDisabledAccountImmediately() {
        when(sessionMapper.findActiveByHash(anyString())).thenReturn(activeSession("raw-1"));
        when(accountMapper.findByUsername("alice")).thenReturn(UserAccount.builder().username("alice").enabled(0).build());

        // 会话仍有效但账号被禁用：封禁即时生效
        assertThat(service.resolve("raw-1")).isNull();
    }

    @Test
    void resolveRejectsUnknownExpiredOrRevokedSession() {
        // findActiveByHash 的 SQL 已过滤 expires_at/revoked_at，查不到即无效
        when(sessionMapper.findActiveByHash(anyString())).thenReturn(null);

        assertThat(service.resolve("raw-x")).isNull();
        verifyNoInteractions(accountMapper);
    }

    @Test
    void resolveIgnoresBlankCookie() {
        assertThat(service.resolve("  ")).isNull();
        assertThat(service.resolve(null)).isNull();
        verifyNoInteractions(sessionMapper);
    }

    @Test
    void revokeActiveSessionIsIdempotent() {
        when(sessionMapper.findActiveByHash(HashUtils.sha256Hex("raw-1"))).thenReturn(activeSession("raw-1"));
        when(sessionMapper.revoke(eq(9L), any())).thenReturn(1);

        assertThat(service.revoke("raw-1")).isTrue();

        when(sessionMapper.findActiveByHash(anyString())).thenReturn(null);
        assertThat(service.revoke("raw-1")).isFalse();
        assertThat(service.revoke("")).isFalse();
    }

    @Test
    void rawSessionIdIsReadFromSessionCookieOnly() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("other", "x"), new Cookie(UserSessionService.SESSION_COOKIE, "raw-1"));

        assertThat(service.rawSessionIdOf(request)).isEqualTo("raw-1");
        assertThat(service.rawSessionIdOf(new MockHttpServletRequest())).isEmpty();
    }

    @Test
    void sessionCookieIsHttpOnlyStrictAndSecureFollowsConfig() {
        MockHttpServletResponse httpResponse = new MockHttpServletResponse();
        service.writeSessionCookie(httpResponse, "raw-1", LocalDateTime.now().plusDays(30));

        String header = httpResponse.getHeaders("Set-Cookie").stream()
                .filter(value -> value.contains("SameSite")).findFirst().orElseThrow();
        assertThat(header).contains("kops_session=raw-1", "HttpOnly", "SameSite=Strict", "Path=/")
                // 默认 false：生产当前 HTTP 部署，Secure cookie 会被浏览器丢弃
                .doesNotContain("Secure");

        properties.setSessionCookieSecure(true);
        MockHttpServletResponse secureResponse = new MockHttpServletResponse();
        service.writeSessionCookie(secureResponse, "raw-2", LocalDateTime.now().plusDays(30));
        // MockHttpServletResponse 会把 Set-Cookie 重新序列化（属性顺序由它决定），逐项断言
        assertThat(secureResponse.getHeaders("Set-Cookie").stream()
                .anyMatch(value -> value.contains("kops_session=raw-2")
                        && value.contains("SameSite=Strict") && value.contains("Secure"))).isTrue();
    }

    @Test
    void clearCookieExpiresImmediately() {
        MockHttpServletResponse httpResponse = new MockHttpServletResponse();
        service.clearSessionCookie(httpResponse);

        assertThat(httpResponse.getHeaders("Set-Cookie"))
                .anyMatch(value -> value.contains("kops_session=;") && value.contains("Max-Age=0"));
        verify(sessionMapper, never()).revoke(anyLong(), any());
    }
}
