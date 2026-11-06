package com.enterprise.iqk.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {
    private boolean enabled = true;
    private String jwtSecret;
    private int jwtExpireMinutes = 120;
    private int refreshExpireDays = 14;
    private int apiKeyExpireDays = 30;
    /** 会话登录（Session + Cookie）：会话有效期（绝对过期，到期重新登录） */
    private int sessionExpireDays = 30;
    /**
     * 会话 Cookie 是否带 Secure 标志。默认 false：生产当前是 HTTP 部署
     * （82.157.60.115:8088），Secure cookie 会被浏览器直接丢弃
     * （旧 refresh cookie 即因此从未真正生效）。切换 HTTPS 部署后置 true。
     */
    private boolean sessionCookieSecure = false;
}
