package com.enterprise.iqk.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 面向 LLM API 调用的 Resilience4j 熔断与重试配置。
 *
 * <p><b>当前状态：配置已定义、尚未织入调用链。</b>三个 Registry/Config Bean 保持参数基线，
 * 但 LLM 调用点（ReactAgentService#callModel、ResearchPlannerAgent#plan、
 * ReportWriterAgent#writeReport 等）暂未通过注解或编程式装饰接入，
 * 相关依赖与配置在当前版本中不产生实际防护效果。
 * 接入时优先考虑在 callModel 链路用 CircuitBreakerRegistry 编程式装饰，
 * 并同步启用 resilience4j-micrometer 指标（r4j.circuit_breaker.*）。</p>
 */
@Configuration
public class ResilienceConfiguration {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slowCallRateThreshold(50)
                .slowCallDurationThreshold(Duration.ofSeconds(10))
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(5)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .recordExceptions(Exception.class)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(2))
                .retryExceptions(Exception.class)
                .ignoreExceptions(IllegalArgumentException.class)
                .build();
        return RetryRegistry.of(config);
    }

    @Bean
    public TimeLimiterConfig timeLimiterConfig() {
        return TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(30))
                .build();
    }
}
