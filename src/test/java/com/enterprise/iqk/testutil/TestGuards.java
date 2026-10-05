package com.enterprise.iqk.testutil;

import com.enterprise.iqk.config.ResilienceConfiguration;
import com.enterprise.iqk.llm.ModelCallGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 测试用真实 ModelCallGuard 工厂：服务构造器新增 guard 参数后，
 * 单测需要能透传 LLM 调用的 guard 实例（mock guard 会吞掉 supplier 返回 null，
 * 破坏成功路径断言）。共用 ResilienceConfiguration 的真实熔断/重试基线；
 * 测试内的异常（普通 RuntimeException）不在 Retry 白名单内，零重试直接上抛，
 * 不会拖慢用例。
 */
public final class TestGuards {

    private TestGuards() {
    }

    /** 每次返回独立实例，避免用例间共享熔断状态。 */
    public static ModelCallGuard real() {
        ResilienceConfiguration config = new ResilienceConfiguration();
        return new ModelCallGuard(config.circuitBreakerRegistry(), config.retryRegistry(),
                config.timeLimiterConfig(), new SimpleMeterRegistry());
    }
}
