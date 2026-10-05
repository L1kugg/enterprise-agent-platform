package com.enterprise.iqk.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClientException;
import org.springframework.web.reactive.function.client.WebClientException;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * 面向 LLM API 调用的 Resilience4j 熔断与重试配置。
 *
 * <p><b>当前状态：已由 {@code llm.ModelCallGuard} 编程式织入 LLM 调用链</b>
 * （ReactAgentService / WorkflowReactAgentService 的 callModel 与 callModelStream、
 * HybridRagAnswerService 与 RagAnswerService 的生成步、ResearchPlannerAgent、
 * ReportWriterAgent）。同步调用为 Retry(CircuitBreaker(call))，流式调用为
 * 熔断许可检查 + 超时 + 终止信号打点（不重试，避免分片重放）。
 * 熔断实例按场景命名（llm.&lt;scenario&gt;，惰性创建、共用下方基线参数），
 * resilience4j-micrometer 指标由下方 Tagged*Metrics Binder Bean 发布
 * （bindTo 时订阅 onEntryAdded，惰性创建的实例也能挂上）。</p>
 *
 * <p>Retry 只重试瞬时异常（网络/超时/429 与 5xx 的 TransientAiException），
 * 参数错误、鉴权失败等非瞬时错误直接上抛不烧重试次数。</p>
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
                // 只重试瞬时故障：连接/读超时（ResourceAccessException 为其子类）、
                // WebClient 通信异常、流超时、Spring AI 的 429/5xx 瞬时异常
                .retryExceptions(RestClientException.class, WebClientException.class,
                        TimeoutException.class, TransientAiException.class)
                .build();
        return RetryRegistry.of(config);
    }

    /** 30s 基线：流式链路的超时上限取自此（ModelCallGuard 注入引用，避免两处硬编码）。 */
    @Bean
    public TimeLimiterConfig timeLimiterConfig() {
        return TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 把熔断器指标发布到 Micrometer（resilience4j_circuitbreaker_* tagged 指标）。
     *
     * <p>为什么自己定义：resilience4j starter 的默认发布路径是把它自带的注册表
     * Bean 与 {@code TaggedCircuitBreakerMetricsPublisher} 一并装配，而本项目的
     * 注册表是自定义 Bean（顶掉了 starter 的 {@code @ConditionalOnMissingBean}），
     * publisher 从未挂上，惰性创建的实例不会有任何指标。Binder Bean 由 Spring Boot
     * 自动 {@code bindTo(MeterRegistry)}，且 bindTo 内部订阅 onEntryAdded 事件，
     * 惰性创建的熔断器同样自动挂指标。</p>
     */
    @Bean
    public TaggedCircuitBreakerMetrics circuitBreakerMetrics(CircuitBreakerRegistry circuitBreakerRegistry) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry);
    }

    /** 重试指标（resilience4j_retry_* tagged 指标），同 {@link #circuitBreakerMetrics}。 */
    @Bean
    public TaggedRetryMetrics retryMetrics(RetryRegistry retryRegistry) {
        return TaggedRetryMetrics.ofRetryRegistry(retryRegistry);
    }
}
