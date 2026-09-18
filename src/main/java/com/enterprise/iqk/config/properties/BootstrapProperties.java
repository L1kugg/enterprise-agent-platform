package com.enterprise.iqk.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 由运维人员提供的引导凭据，适用于仓库中提交的演示密钥已被吊销
 * （Flyway V15）、且生产环境不得再植入这些密钥的部署场景。
 * 绑定前缀 {@code app.bootstrap} 映射
 * APP_BOOTSTRAP_API_KEY / APP_BOOTSTRAP_KEY_NAME / APP_BOOTSTRAP_TENANT_ID，
 * 使 Python 运行时与跨运行时契约测试栈共享同一套环境变量接口。
 */
@Data
@ConfigurationProperties(prefix = "app.bootstrap")
public class BootstrapProperties {
    private String apiKey;
    private String keyName = "bootstrap-admin";
    private String tenantId = "public";
    private String role = "ADMIN";
}
