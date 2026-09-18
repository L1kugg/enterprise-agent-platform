package com.enterprise.iqk.controller;

import com.enterprise.iqk.config.properties.SecurityProperties;
import com.enterprise.iqk.domain.vo.ApiKeyIssueVO;
import com.enterprise.iqk.domain.vo.AuthTokenVO;
import com.enterprise.iqk.security.ApiKeyAuthService;
import com.enterprise.iqk.security.ApiKeyLifecycleService;
import com.enterprise.iqk.security.AuthIdentity;
import com.enterprise.iqk.security.JwtService;
import com.enterprise.iqk.security.PermissionService;
import com.enterprise.iqk.security.RefreshTokenService;
import com.enterprise.iqk.security.TenantContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private static final long REFRESH_EXPIRE_SOON_DAYS = 2L;
    /** 存放长效 refresh token 的 HttpOnly cookie 的名称。 */
    public static final String REFRESH_COOKIE = "kops_refresh";

    private final ApiKeyAuthService apiKeyAuthService;
    private final ApiKeyLifecycleService apiKeyLifecycleService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final PermissionService permissionService;
    private final SecurityProperties securityProperties;

    /**
     * 为 true（默认值）时，refresh token cookie 会标记 Secure 并使用
     * SameSite=Strict 策略。在本地开发中通过纯 HTTP 提供服务的操作员
     * 应设置 {@code app.security.refresh-cookie-secure=false}，以便
     * localhost 浏览器能够使用该 cookie。
     */
    @Value("${app.security.refresh-cookie-secure:true}")
    private boolean refreshCookieSecure;

    @PostMapping("/token")
    public AuthTokenVO token(@RequestHeader("X-API-Key") String apiKey,
                             @RequestHeader(value = TenantContext.TENANT_HEADER, required = false) String tenantHeader,
                             HttpServletResponse response) {
        AuthIdentity identity = apiKeyAuthService.authenticate(apiKey);
        if (identity == null) {
            return AuthTokenVO.builder().ok(0).msg("invalid api key").build();
        }
        String identityTenant = TenantContext.normalize(identity.getTenantId());
        if (StringUtils.hasText(tenantHeader) && !identityTenant.equals(TenantContext.normalize(tenantHeader))) {
            return AuthTokenVO.builder().ok(0).msg("tenant mismatch for api key").build();
        }
        List<String> permissions = permissionService.permissionsForRoles(identity.getRoles());
        String token = jwtService.issueToken(identity.getPrincipal(), identity.getRoles(), permissions, identityTenant);
        RefreshTokenService.RefreshTokenIssueResult refreshIssue =
                refreshTokenService.issue(identity.getPrincipal(), identity.getRoles(), identityTenant);
        // Refresh token 以 HttpOnly cookie 下发，这样前端即使发生 XSS
        // 也无法窃取这份长效凭据。响应体中仍会携带一份副本，
        // 供尚未切换到 credentials: 'include' 的现有 App.vue 使用。
        // 等配套的 PR（PR B）合入且 App.vue 弃用 localStorage 后，
        // 即可移除响应体中的该字段，且不会在升级过程中破坏旧客户端。
        setRefreshCookie(response, refreshIssue);
        return buildTokenResponse(token, identityTenant, refreshIssue);
    }

    @PostMapping("/refresh")
    public AuthTokenVO refresh(@RequestHeader(value = "X-Refresh-Token", required = false) String refreshTokenHeader,
                              @CookieValue(value = REFRESH_COOKIE, required = false) String refreshCookie) {
        // 接受来自旧版 X-Refresh-Token 请求头或 /auth/token 所设置
        // HttpOnly cookie 的 refresh token。请求头优先，这样旧客户端
        // （或测试脚本）仍可覆盖 cookie 中的值。
        String refreshToken = StringUtils.hasText(refreshTokenHeader) ? refreshTokenHeader : refreshCookie;
        AuthIdentity identity = refreshTokenService.consume(refreshToken);
        if (identity == null) {
            return AuthTokenVO.builder().ok(0).msg("invalid refresh token").build();
        }
        String tenantId = TenantContext.normalize(identity.getTenantId());
        List<String> permissions = permissionService.permissionsForRoles(identity.getRoles());
        String token = jwtService.issueToken(identity.getPrincipal(), identity.getRoles(), permissions, tenantId);
        RefreshTokenService.RefreshTokenIssueResult refreshIssue =
                refreshTokenService.issue(identity.getPrincipal(), identity.getRoles(), tenantId);
        return buildTokenResponse(token, tenantId, refreshIssue);
    }

    private void setRefreshCookie(HttpServletResponse response, RefreshTokenService.RefreshTokenIssueResult refreshIssue) {
        long maxAge = Math.max(0L,
                java.time.Duration.between(
                        java.time.Instant.now(),
                        refreshIssue.expiresAt().atZone(java.time.ZoneOffset.UTC).toInstant()
                ).getSeconds());
        // Servlet Cookie API 没有 SameSite 的设置方法，因此需要手工
        // 拼接 Set-Cookie 头。开发环境配置（refresh-cookie-secure=false）
        // 下省略 Secure 标志，因为 Secure cookie 不会随纯 HTTP 请求发送，
        // 而开发前端正是通过纯 HTTP 访问开发后端的。
        String secureAttr = refreshCookieSecure ? "; Secure" : "";
        String cookieValue = String.format(
                "%s=%s; Path=/auth; Max-Age=%d; HttpOnly%s; SameSite=Strict",
                REFRESH_COOKIE,
                refreshIssue.rawToken(),
                Math.min(maxAge, Integer.MAX_VALUE),
                secureAttr);
        Cookie cookie = new Cookie(REFRESH_COOKIE, refreshIssue.rawToken());
        cookie.setHttpOnly(true);
        cookie.setSecure(refreshCookieSecure);
        cookie.setPath("/auth");
        cookie.setMaxAge((int) Math.min(maxAge, Integer.MAX_VALUE));
        response.addCookie(cookie);
        // 标准 Cookie API 不支持 SameSite；追加第二个 Set-Cookie 头
        // （带 SameSite 属性），使容器在序列化时必须合并。第一个
        // Set-Cookie（来自 addCookie）与第二个（来自 addHeader）的
        // 值和属性完全相同；按照 RFC 7234，下游缓存会保留其中一个。
        response.addHeader("Set-Cookie", cookieValue);
    }

    @PostMapping("/api-keys")
    @PreAuthorize("hasAnyAuthority('PERM_AUTH_KEY_MANAGE','ROLE_ADMIN')")
    public ApiKeyIssueVO issueApiKey(@RequestParam("keyName") String keyName,
                                     @RequestParam(value = "role", defaultValue = "USER") String roleName) {
        ApiKeyLifecycleService.ApiKeyIssueResult result = apiKeyLifecycleService.issue(
                keyName, roleName, TenantContext.currentTenantId());
        return ApiKeyIssueVO.builder()
                .ok(1)
                .msg("ok")
                .keyName(result.keyName())
                .tenantId(result.tenantId())
                .rawApiKey(result.rawApiKey())
                .expiresAt(result.expiresAt())
                .build();
    }

    @PostMapping("/api-keys/rotate")
    @PreAuthorize("hasAnyAuthority('PERM_AUTH_KEY_MANAGE','ROLE_ADMIN')")
    public ApiKeyIssueVO rotateApiKey(@RequestParam("keyName") String keyName,
                                      @RequestParam(value = "reason", defaultValue = "rotation") String reason) {
        ApiKeyLifecycleService.ApiKeyIssueResult result = apiKeyLifecycleService.rotate(
                keyName, reason, TenantContext.currentTenantId());
        return ApiKeyIssueVO.builder()
                .ok(1)
                .msg("rotated")
                .keyName(result.keyName())
                .tenantId(result.tenantId())
                .rawApiKey(result.rawApiKey())
                .expiresAt(result.expiresAt())
                .build();
    }

    @PostMapping("/api-keys/revoke")
    @PreAuthorize("hasAnyAuthority('PERM_AUTH_KEY_MANAGE','ROLE_ADMIN')")
    public ApiKeyIssueVO revokeApiKey(@RequestParam("keyName") String keyName,
                                      @RequestParam(value = "reason", defaultValue = "manual revoke") String reason) {
        String normalizedTenant = TenantContext.currentTenantId();
        apiKeyLifecycleService.revoke(keyName, reason, normalizedTenant);
        return ApiKeyIssueVO.builder()
                .ok(1)
                .msg("revoked")
                .keyName(keyName)
                .tenantId(normalizedTenant)
                .build();
    }

    private AuthTokenVO buildTokenResponse(String token,
                                           String tenantId,
                                           RefreshTokenService.RefreshTokenIssueResult refreshIssue) {
        LocalDateTime refreshExpiresAt = refreshIssue.expiresAt();
        boolean refreshWillExpireSoon = refreshExpiresAt != null
                && refreshExpiresAt.isBefore(LocalDateTime.now().plusDays(REFRESH_EXPIRE_SOON_DAYS));
        return AuthTokenVO.builder()
                .ok(1)
                .msg("ok")
                .token(token)
                .refreshToken(refreshIssue.rawToken())
                .tenantId(tenantId)
                .expiresInSeconds(securityProperties.getJwtExpireMinutes() * 60L)
                .refreshExpiresAt(refreshExpiresAt)
                .refreshWillExpireSoon(refreshWillExpireSoon)
                .build();
    }
}
