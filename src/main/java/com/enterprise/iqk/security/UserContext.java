package com.enterprise.iqk.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

/**
 * 用户上下文工具：记忆子系统的 user 键从"会话 chatId"升级为"认证主体"
 * （JWT subject 或 API key 主体），使 long 画像真正跨会话 ——
 * 此前画像按 chatId 存取，新会话换 chatId 就召不回，画像随会话陪葬。
 *
 * 匿名/未认证（如关闭鉴权的本地环境、后台线程无 SecurityContext）时
 * 回落到调用方给的 fallback（chatId），行为与旧实现兼容。
 */
public final class UserContext {

    /** Spring Security 匿名主体的保留名，不作为记忆 user 键 */
    private static final String ANONYMOUS_PRINCIPAL = "anonymousUser";

    private UserContext() {
    }

    /**
     * 当前线程的认证主体作为记忆 user 键；无认证主体时返回 fallback。
     *
     * @param fallback 未认证时的回落键（传 chatId 保持旧作用域；传空串表示宁可不注入）
     */
    public static String currentUserId(String fallback) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() != null) {
            String principal = String.valueOf(authentication.getPrincipal());
            if (StringUtils.hasText(principal) && !ANONYMOUS_PRINCIPAL.equals(principal)) {
                return principal;
            }
        }
        return fallback;
    }
}
