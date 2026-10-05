package com.enterprise.iqk.llm;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * LLM 调用守卫：把 ResilienceConfiguration 的熔断/重试/超时基线编程式织入模型调用，
 * 各调用点一行接入，不引入注解 Magic。
 *
 * <p>同步调用 {@link #call}：Retry(CircuitBreaker(call))——熔断器记录每次尝试，
 * 重试由 Retry 白名单（瞬时异常）驱动；熔断打开抛 CallNotPermittedException，
 * 由调用方既有兜底承接（React/工作流走规则兜底，RAG 生成返回固定文案）。
 * 流式调用 {@link #streaming}：每次订阅时检查熔断许可（打开即快速失败），
 * 超时上限取 TimeLimiterConfig 基线（30s）；不重试——分片已发出后重试会造成重复输出。
 * 流式成功/失败按终止信号（complete/error）回写熔断器。</p>
 *
 * <p>熔断实例按场景命名 llm.&lt;scenario&gt;（react / workflow / rag / rag-hybrid /
 * research-plan / research-report），惰性创建、共用 registry 基线参数；
 * resilience4j-spring-boot3 的 Tagged 指标发布器监听 registry 的实例创建事件，
 * 运行时新建的实例也会自动获得 resilience4j 指标。每次调用另记
 * {@code llm.call.outcome{scenario, outcome}} 计数器（success / error / not_permitted）
 * 便于冒烟与告警。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelCallGuard {

    private static final String METRIC_PREFIX = "llm.call.outcome";
    private static final String INSTANCE_PREFIX = "llm.";

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final TimeLimiterConfig timeLimiterConfig;
    private final MeterRegistry meterRegistry;

    /**
     * 同步 LLM 调用：熔断记录每次尝试 + 白名单瞬时异常重试。
     * scenario 为场景标识（与实例名、指标标签一致）。
     */
    public <T> T call(String scenario, Supplier<T> llmCall) {
        CircuitBreaker breaker = breaker(scenario);
        Retry retry = retryRegistry.retry(INSTANCE_PREFIX + scenario);
        long start = System.nanoTime();
        try {
            T result = Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, llmCall)).get();
            outcome(scenario, "success");
            return result;
        } catch (CallNotPermittedException ex) {
            outcome(scenario, "not_permitted");
            throw ex;
        } catch (RuntimeException ex) {
            outcome(scenario, "error");
            throw ex;
        } finally {
            log.debug("LLM guarded call: scenario={}, tookMs={}", scenario,
                    (System.nanoTime() - start) / 1_000_000);
        }
    }

    /**
     * 流式 LLM 调用：每次订阅时检查熔断许可 + 超时上限，按终止信号回写熔断器并记指标。
     * 返回的流在订阅时才可能 error（熔断打开），调用方的错误通道照常生效。
     */
    public Flux<String> streaming(String scenario, Flux<String> llmStream) {
        CircuitBreaker breaker = breaker(scenario);
        return Flux.defer(() -> {
            long startNanos;
            try {
                // defer 内检查：每次订阅都按当前熔断状态决定放行或快速失败
                breaker.acquirePermission();
            } catch (CallNotPermittedException ex) {
                outcome(scenario, "not_permitted");
                throw ex;
            }
            startNanos = System.nanoTime();
            return llmStream
                    .timeout(timeLimiterConfig.getTimeoutDuration())
                    .doOnComplete(() -> {
                        breaker.onSuccess(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
                        outcome(scenario, "success");
                    })
                    .doOnError(ex -> {
                        breaker.onError(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS, ex);
                        outcome(scenario, "error");
                    });
        });
    }

    /** 惰性取/建场景熔断实例（registry 缓存同名实例；指标由 starter 发布器动态绑定）。 */
    private CircuitBreaker breaker(String scenario) {
        return circuitBreakerRegistry.circuitBreaker(INSTANCE_PREFIX + scenario);
    }

    private void outcome(String scenario, String outcome) {
        meterRegistry.counter(METRIC_PREFIX, "scenario", scenario, "outcome", outcome).increment();
    }
}
