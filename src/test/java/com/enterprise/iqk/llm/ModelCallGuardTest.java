package com.enterprise.iqk.llm;

import com.enterprise.iqk.config.ResilienceConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ModelCallGuard 单测：锁定同步透传与计数、白名单重试、非白名单不重试、
 * 熔断打开快速失败（同步与流式）、流式超时按失败回写熔断器。
 */
class ModelCallGuardTest {

    // 注册表/配置只取一次：@Bean 方法在普通实例上就是普通方法，每次调用都 new 新实例
    private final ResilienceConfiguration config = new ResilienceConfiguration();
    private final CircuitBreakerRegistry circuitBreakerRegistry = config.circuitBreakerRegistry();
    private final RetryRegistry retryRegistry = config.retryRegistry();
    private final TimeLimiterConfig timeLimiterConfig = config.timeLimiterConfig();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private ModelCallGuard newGuard() {
        return new ModelCallGuard(circuitBreakerRegistry, retryRegistry,
                timeLimiterConfig, meterRegistry);
    }

    private double counter(String scenario, String outcome) {
        return meterRegistry.counter("llm.call.outcome", "scenario", scenario, "outcome", outcome).count();
    }

    @Test
    void syncCallPassesThroughAndCountsSuccess() {
        String result = newGuard().call("react", () -> "答案");

        assertThat(result).isEqualTo("答案");
        assertThat(counter("react", "success")).isEqualTo(1.0);
    }

    @Test
    void transientFailureIsRetriedUpToThreeAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        ModelCallGuard guard = newGuard();

        String result = guard.call("react", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new TransientAiException("upstream busy");
            }
            return "第三次成功";
        });

        assertThat(result).isEqualTo("第三次成功");
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(counter("react", "success")).isEqualTo(1.0);
    }

    @Test
    void nonTransientFailureIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        ModelCallGuard guard = newGuard();

        assertThatThrownBy(() -> guard.call("react", () -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("参数错误不重试");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(attempts.get()).isEqualTo(1);
        assertThat(counter("react", "error")).isEqualTo(1.0);
    }

    @Test
    void openCircuitFailsFastWithoutInvokingSupplier() {
        ModelCallGuard guard = newGuard();
        // 先触发一次真实调用创建实例，再把实例手动切到 open 模拟"模型持续不可用"
        guard.call("rag-hybrid", () -> "ok");
        circuitBreakerRegistry.circuitBreaker("llm.rag-hybrid").transitionToOpenState();

        AtomicInteger invoked = new AtomicInteger();
        assertThatThrownBy(() -> guard.call("rag-hybrid", () -> {
            invoked.incrementAndGet();
            return "不应被调用";
        })).isInstanceOf(CallNotPermittedException.class);

        assertThat(invoked.get()).isZero();
        assertThat(counter("rag-hybrid", "not_permitted")).isEqualTo(1.0);
    }

    @Test
    void streamingPassesElementsAndCountsSuccessOnComplete() {
        List<String> frames = newGuard()
                .streaming("workflow", Flux.just("你", "好"))
                .collectList()
                .block();

        assertThat(frames).containsExactly("你", "好");
        assertThat(counter("workflow", "success")).isEqualTo(1.0);
    }

    @Test
    void streamingWithOpenCircuitErrorsImmediatelyOnSubscribe() {
        ModelCallGuard guard = newGuard();
        guard.streaming("rag", Flux.just("x")).collectList().block();
        circuitBreakerRegistry.circuitBreaker("llm.rag").transitionToOpenState();

        assertThatThrownBy(() -> guard.streaming("rag", Flux.just("y")).collectList().block())
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(counter("rag", "not_permitted")).isEqualTo(1.0);
    }

    @Test
    void streamingTimeoutIsRecordedAsBreakerFailure() {
        // 独立 50ms 超时基线，验证超时按失败回写熔断器
        ModelCallGuard guard = new ModelCallGuard(
                circuitBreakerRegistry, retryRegistry,
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(50)).build(),
                meterRegistry);

        assertThatThrownBy(() -> guard.streaming("react", Flux.never()).collectList().block())
                .isInstanceOf(RuntimeException.class);
        assertThat(counter("react", "error")).isEqualTo(1.0);
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("llm.react");
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void taggedMetricsPublishForLazilyCreatedBreakerAndRetry() {
        // 生产装配方式：MeterBinder Bean 由 Spring Boot bindTo(MeterRegistry)，
        // 惰性创建的熔断器/重试器经 onEntryAdded 自动挂上 tagged 指标
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry).bindTo(meterRegistry);
        TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(meterRegistry);

        AtomicInteger attempts = new AtomicInteger();
        newGuard().call("research-plan", () -> {
            if (attempts.incrementAndGet() < 2) {
                throw new TransientAiException("upstream busy");
            }
            return "第二次成功";
        });

        assertThat(meterRegistry.get("resilience4j.circuitbreaker.state")
                .tag("name", "llm.research-plan").gauge().value()).isEqualTo(0.0); // 0 = closed
        assertThat(meterRegistry.get("resilience4j.retry.calls")
                .tag("name", "llm.research-plan")
                .tag("kind", "successful_with_retry").functionCounter().count()).isEqualTo(1.0);
    }
}
