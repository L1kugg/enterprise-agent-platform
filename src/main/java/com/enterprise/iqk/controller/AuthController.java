package com.enterprise.iqk.controller;

import com.enterprise.iqk.config.properties.SecurityProperties;
import com.enterprise.iqk.domain.UserAccount;
import com.enterprise.iqk.domain.vo.ApiKeyIssueVO;
import com.enterprise.iqk.domain.vo.AuthCredentialsVO;
import com.enterprise.iqk.domain.vo.AuthTokenVO;
import com.enterprise.iqk.security.ApiKeyAuthService;
import com.enterprise.iqk.security.ApiKeyLifecycleService;
import com.enterprise.iqk.security.AuthIdentity;
import com.enterprise.iqk.security.JwtService;
import com.enterprise.iqk.security.PermissionService;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.security.UserAuthService;
import com.enterprise.iqk.security.UserSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 认证入口。人类通道走服务端会话：login/register 建立会话并下发 HttpOnly Session
 * Cookie，/me 探测当前身份，/logout 吊销会话。程序化通道保留：/token 用 API Key
 * 换短周期 JWT，/api-keys 管理 API Key。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final ApiKeyAuthService apiKeyAuthService;
    private final ApiKeyLifecycleService apiKeyLifecycleService;
    private final JwtService jwtService;
    private final PermissionService permissionService;
    private final SecurityProperties securityProperties;
    private final UserAuthService userAuthService;
    private final UserSessionService userSessionService;

    /**
     * 程序化通道（API Key → JWT）：脚本/CI 用 API Key 换一个短周期 JWT 后以
     * Bearer 方式调用。人类登录不走这里——走 /auth/login 的 Session Cookie。
     */
    @PostMapping("/token")
    public AuthTokenVO token(@RequestHeader("X-API-Key") String apiKey,
                             @RequestHeader(value = TenantContext.TENANT_HEADER, required = false) String tenantHeader) {
        AuthIdentity identity = apiKeyAuthService.authenticate(apiKey);
        if (identity == null) {
            return AuthTokenVO.builder().ok(0).msg("API Key 不正确").build();
        }
        String identityTenant = TenantContext.normalize(identity.getTenantId());
        if (StringUtils.hasText(tenantHeader) && !identityTenant.equals(TenantContext.normalize(tenantHeader))) {
            return AuthTokenVO.builder().ok(0).msg("API Key 与租户不匹配").build();
        }
        List<String> permissions = permissionService.permissionsForRoles(identity.getRoles());
        String token = jwtService.issueToken(identity.getPrincipal(), identity.getRoles(), permissions, identityTenant);
        return AuthTokenVO.builder()
                .ok(1)
                .msg("ok")
                .token(token)
                .tenantId(identityTenant)
                .role(firstRole(identity.getRoles()))
                .expiresInSeconds(securityProperties.getJwtExpireMinutes() * 60L)
                .build();
    }

    /** 开放注册：用户名 + 密码 → 建号（USER 角色、公司租户 public）并直接建立会话。 */
    @PostMapping("/register")
    public AuthTokenVO register(@RequestBody(required = false) AuthCredentialsVO request,
                                HttpServletResponse response) {
        if (request == null || !StringUtils.hasText(request.getUsername())
                || !StringUtils.hasText(request.getPassword())) {
            return AuthTokenVO.builder().ok(0).msg("请输入用户名和密码").build();
        }
        UserAccount user;
        try {
            user = userAuthService.register(request.getUsername(), request.getPassword());
        } catch (IllegalArgumentException ex) {
            // 与 /auth/token 的 invalid api key 同一契约：HTTP 200 + ok=0 + msg
            return AuthTokenVO.builder().ok(0).msg(ex.getMessage()).build();
        }
        return issueSession(user, response);
    }

    /** 密码登录：校验通过即建立服务端会话；失败统一返回"用户名或密码不正确"，防用户名枚举。 */
    @PostMapping("/login")
    public AuthTokenVO login(@RequestBody(required = false) AuthCredentialsVO request,
                             HttpServletResponse response) {
        if (request == null || !StringUtils.hasText(request.getUsername())
                || !StringUtils.hasText(request.getPassword())) {
            return AuthTokenVO.builder().ok(0).msg("请输入用户名和密码").build();
        }
        UserAccount user = userAuthService.verify(request.getUsername(), request.getPassword());
        if (user == null) {
            return AuthTokenVO.builder().ok(0).msg("用户名或密码不正确").build();
        }
        return issueSession(user, response);
    }

    /**
     * 当前身份探测（Session Cookie 通道）：前端启动时调用以校正登录态与角色。
     * 未登录返回 ok=0（HTTP 200，前端按"未登录"处理而非报错）。
     */
    @GetMapping("/me")
    public AuthTokenVO me(HttpServletRequest request) {
        AuthIdentity identity = userSessionService.resolve(userSessionService.rawSessionIdOf(request));
        if (identity == null) {
            return AuthTokenVO.builder().ok(0).msg("未登录").build();
        }
        return AuthTokenVO.builder()
                .ok(1)
                .msg("ok")
                .username(identity.getPrincipal())
                .tenantId(identity.getTenantId())
                .role(firstRole(identity.getRoles()))
                .build();
    }

    /**
     * 登出：吊销当前会话（幂等）并清除 Cookie。未登录时调用同样清 Cookie 并返回 ok=1
     * ——语义即"已处于登出态"，前端无需区分。
     */
    @PostMapping("/logout")
    public AuthTokenVO logout(HttpServletRequest request, HttpServletResponse response) {
        boolean revoked = userSessionService.revoke(userSessionService.rawSessionIdOf(request));
        userSessionService.clearSessionCookie(response);
        return AuthTokenVO.builder().ok(1).msg(revoked ? "logged out" : "no active session").build();
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

    /** 注册 / 密码登录共用：建立服务端会话 + 下发 HttpOnly Session Cookie（响应体不含任何令牌）。 */
    private AuthTokenVO issueSession(UserAccount user, HttpServletResponse response) {
        List<String> roles = userAuthService.roleNamesOf(user);
        String tenantId = TenantContext.normalize(user.getTenantId());
        UserSessionService.SessionIssue session =
                userSessionService.create(user.getUsername(), roles, tenantId);
        userSessionService.writeSessionCookie(response, session.rawSessionId(), session.expiresAt());
        return AuthTokenVO.builder()
                .ok(1)
                .msg("ok")
                .username(user.getUsername())
                .tenantId(tenantId)
                .role(firstRole(roles))
                .build();
    }

    /** 取首个非空角色作为前端显隐管理员 UI 的依据；无角色返回空串。 */
    private String firstRole(List<String> roles) {
        if (roles == null) {
            return "";
        }
        return roles.stream().filter(StringUtils::hasText).findFirst().orElse("");
    }
}
