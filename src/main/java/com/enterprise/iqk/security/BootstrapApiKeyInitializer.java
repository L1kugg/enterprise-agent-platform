package com.enterprise.iqk.security;

import com.enterprise.iqk.config.properties.BootstrapProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 当配置了 APP_BOOTSTRAP_API_KEY 时，在启动时注入操作员提供的
 * bootstrap ADMIN 凭据。仓库中提交的演示 key 已被 Flyway V15 吊销；
 * 本类是按部署显式注入的等价机制（Python 运行时通过同名环境变量
 * 提供相同机制）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapApiKeyInitializer implements ApplicationRunner {

    private final ApiKeyLifecycleService apiKeyLifecycleService;
    private final BootstrapProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        String rawKey = properties.getApiKey();
        if (!StringUtils.hasText(rawKey)) {
            return;
        }
        try {
            ApiKeyLifecycleService.ApiKeyIssueResult result =
                    apiKeyLifecycleService.provision(rawKey, properties.getKeyName(), properties.getRole(), properties.getTenantId());
            if (result.rawApiKey() != null) {
                log.info("bootstrap api key '{}' provisioned for tenant '{}' (expires {})",
                        result.keyName(), result.tenantId(), result.expiresAt());
            } else {
                log.info("bootstrap api key '{}' already active for tenant '{}'", result.keyName(), result.tenantId());
            }
        } catch (RuntimeException exc) {
            // 失败即终止（fail closed）：配置错误的 bootstrap 凭据必须在
            // 启动时暴露出来，而不是让部署环境缺少管理员。
            throw new IllegalStateException("failed to provision bootstrap api key '" + properties.getKeyName() + "'", exc);
        }
    }
}
