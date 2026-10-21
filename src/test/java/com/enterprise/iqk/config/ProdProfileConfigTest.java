package com.enterprise.iqk.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ProdProfileConfigTest {

    /** 启用安全时必须带 app.secret-key（FR-R12-01 凭据加密主密钥），测试属性统一携带。 */
    private static final String SECRET_KEY_PROPERTY = "app.secret-key="
            + java.util.Base64.getEncoder().encodeToString(new byte[32]);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    CorsConfiguration.class,
                    AppStartupValidator.class));

    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    @Test
    void prodRejectsWildcardCors() {
        runner.withPropertyValues(
                "app.cors.allowed-origins=*",
                "app.security.enabled=true",
                SECRET_KEY_PROPERTY,
                "app.security.jwt-secret=a-real-prod-secret-key"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CORS wildcard");
        });
    }

    @Test
    void prodRejectsEmptyJwtSecret() {
        runner.withPropertyValues(
                "app.cors.allowed-origins=https://example.com",
                "app.security.enabled=true",
                SECRET_KEY_PROPERTY,
                "app.security.jwt-secret="
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("jwt-secret must not be blank");
        });
    }

    @Test
    void prodRejectsDefaultJwtSecret() {
        runner.withPropertyValues(
                "app.cors.allowed-origins=https://example.com",
                "app.security.enabled=true",
                SECRET_KEY_PROPERTY,
                "app.security.jwt-secret=dev-jwt-secret-change-this-in-prod-123456"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("well-known default");
        });
    }

    @Test
    void prodRejectsPlaceholderJwtSecret() {
        runner.withPropertyValues(
                "app.cors.allowed-origins=https://example.com",
                "app.security.enabled=true",
                SECRET_KEY_PROPERTY,
                "app.security.jwt-secret=replace-me-with-real-secret"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("placeholder");
        });
    }

    @Test
    void activeProdRejectsDisabledSecurity() {
        runner.withPropertyValues(
                "spring.profiles.active=prod",
                "app.cors.allowed-origins=https://example.com",
                "app.security.enabled=false"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be true");
        });
    }

    @Test
    void activeProdRejectsRabbitGuestCredentials() {
        runner.withPropertyValues(
                "spring.profiles.active=prod",
                "app.cors.allowed-origins=https://example.com",
                "app.security.enabled=true",
                SECRET_KEY_PROPERTY,
                "app.security.jwt-secret=a-real-prod-secret-key",
                "spring.rabbitmq.username=guest",
                "spring.rabbitmq.password=guest"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("guest/guest");
        });
    }

    @Test
    void prodAcceptsExplicitOriginsAndSecret() {
        runner.withPropertyValues(
                "app.cors.allowed-origins=https://example.com,https://app.example.com",
                "app.security.enabled=true",
                SECRET_KEY_PROPERTY,
                "app.security.jwt-secret=a-real-prod-secret-key"
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void activeProdAcceptsExplicitSecurityAndRabbitCredentials() {
        runner.withPropertyValues(
                "spring.profiles.active=prod",
                "app.cors.allowed-origins=https://example.com,https://app.example.com",
                "app.security.enabled=true",
                SECRET_KEY_PROPERTY,
                "app.security.jwt-secret=a-real-prod-secret-key",
                "spring.rabbitmq.username=enterprise-agent-platform",
                "spring.rabbitmq.password=not-guest"
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void devAcceptsLocalhostOrigins() {
        runner.withPropertyValues(
                "app.cors.allowed-origins=http://localhost:8088,http://localhost:5173",
                "app.security.enabled=false"
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void securityDisabledAllowsEmptySecret() {
        runner.withPropertyValues(
                "app.cors.allowed-origins=http://localhost:8088",
                "app.security.enabled=false"
        ).run(context -> assertThat(context).hasNotFailed());
    }
}
