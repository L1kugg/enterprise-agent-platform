package com.enterprise.iqk.security;

import com.enterprise.iqk.config.properties.SecurityProperties;
import com.enterprise.iqk.domain.UserAccount;
import com.enterprise.iqk.domain.UserSessionRecord;
import com.enterprise.iqk.mapper.UserAccountMapper;
import com.enterprise.iqk.mapper.UserSessionMapper;
import com.enterprise.iqk.util.HashUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 服务端登录会话（人类通道，Session + Cookie）：
 * 登录/注册成功时创建会话（库里只存会话 ID 的 sha256），浏览器凭 HttpOnly Cookie
 * 携带原文；每次请求 resolve 出身份——会话可被精确吊销（登出/踢人），
 * 解析时回查 users.enabled（封禁即时生效，与原 JWT 通道同一语义）。
 * Cookie 为 HttpOnly + SameSite=Strict（同源 SPA 无跨站场景，Strict 即 CSRF 缓解）
 * + Secure 按配置（生产当前 HTTP 部署必须 false，否则 cookie 被浏览器丢弃）。
 */
@Service
@RequiredArgsConstructor
public class UserSessionService {

    public static final String SESSION_COOKIE = "kops_session";

    private final SecurityProperties securityProperties;
    private final UserSessionMapper userSessionMapper;
    private final UserAccountMapper userAccountMapper;
    private final PermissionService permissionService;

    /** 会话签发结果：原文仅此一次下发（写 Cookie 用），库中只落哈希。 */
    public record SessionIssue(String rawSessionId, LocalDateTime expiresAt) {
    }

    /** 登录/注册成功后创建会话；roles 固化进会话（权限变更重新登录生效，模型同旧 refresh）。 */
    public SessionIssue create(String principal, List<String> roles, String tenantId) {
        String raw = UUID.randomUUID() + "." + UUID.randomUUID();
        int expireDays = Math.max(1, securityProperties.getSessionExpireDays());
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(expireDays);
        UserSessionRecord record = UserSessionRecord.builder()
                .sessionHash(HashUtils.sha256Hex(raw))
                .principal(principal)
                .tenantId(TenantContext.normalize(tenantId))
                .rolesCsv(roles == null ? "" : String.join(",",
                        roles.stream().filter(StringUtils::hasText).toList()))
                .createdAt(LocalDateTime.now())
                .expiresAt(expiresAt)
                .build();
        userSessionMapper.insert(record);
        return new SessionIssue(raw, record.getExpiresAt());
    }

    /**
     * 从 Cookie 原文解析身份：活跃会话 + 账号 enabled 复查（禁用即刻生效）。
     * 任何一环不满足返回 null，由过滤器统一 401。
     */
    public AuthIdentity resolve(String rawSessionId) {
        if (!StringUtils.hasText(rawSessionId)) {
            return null;
        }
        UserSessionRecord session =
                userSessionMapper.findActiveByHash(HashUtils.sha256Hex(rawSessionId.trim()));
        if (session == null) {
            return null;
        }
        UserAccount account = userAccountMapper.findByUsername(session.getPrincipal());
        if (account == null || account.getEnabled() == null || account.getEnabled() != 1) {
            return null;
        }
        List<String> roles = java.util.Arrays.stream(
                        StringUtils.hasText(session.getRolesCsv()) ? session.getRolesCsv().split(",") : new String[0])
                .filter(StringUtils::hasText)
                .toList();
        return AuthIdentity.builder()
                .principal(session.getPrincipal())
                .roles(roles)
                .permissions(permissionService.permissionsForRoles(roles))
                .source("session")
                .tenantId(TenantContext.normalize(session.getTenantId()))
                .build();
    }

    /** 从请求中提取会话原文（Cookie 通道）。 */
    public String rawSessionIdOf(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return "";
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return "";
    }

    /** 登出：按 Cookie 原文吊销会话（幂等）；返回是否实际吊销了一个活跃会话。 */
    public boolean revoke(String rawSessionId) {
        if (!StringUtils.hasText(rawSessionId)) {
            return false;
        }
        UserSessionRecord session =
                userSessionMapper.findActiveByHash(HashUtils.sha256Hex(rawSessionId.trim()));
        return session != null && userSessionMapper.revoke(session.getId(), LocalDateTime.now()) > 0;
    }

    /** 按人全下线：该租户该主体的全部活跃会话一起失效（改密/踢人场景用）。 */
    public int revokeAllForPrincipal(String tenantId, String principal) {
        return userSessionMapper.revokeAllForPrincipal(
                TenantContext.normalize(tenantId), principal, LocalDateTime.now());
    }

    /** 写会话 Cookie：HttpOnly + SameSite=Strict；Servlet Cookie API 不支持 SameSite，须双写 Set-Cookie 头。 */
    public void writeSessionCookie(HttpServletResponse response, String rawSessionId, LocalDateTime expiresAt) {
        int maxAgeSeconds = (int) Math.max(1,
                Duration.between(LocalDateTime.now(), expiresAt).getSeconds());
        Cookie cookie = new Cookie(SESSION_COOKIE, rawSessionId);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(maxAgeSeconds);
        response.addCookie(cookie);
        String sameSite = "; SameSite=Strict";
        if (securityProperties.isSessionCookieSecure()) {
            sameSite += "; Secure";
        }
        response.addHeader("Set-Cookie",
                SESSION_COOKIE + "=" + rawSessionId + "; Path=/; Max-Age=" + maxAgeSeconds
                        + "; HttpOnly" + sameSite);
    }

    /** 清会话 Cookie（登出）：Max-Age=0 即时失效。 */
    public void clearSessionCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(SESSION_COOKIE, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        response.addHeader("Set-Cookie",
                SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"
                        + (securityProperties.isSessionCookieSecure() ? "; Secure" : ""));
    }
}
