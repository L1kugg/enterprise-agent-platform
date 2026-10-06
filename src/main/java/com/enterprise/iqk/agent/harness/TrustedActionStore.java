package com.enterprise.iqk.agent.harness;

import java.time.Duration;
import java.util.Optional;

/**
 * 受信动作挂起存储：preview 写入、execute 原子消费。
 * 实现必须保证同一 token 只能被成功消费一次（多实例部署下依赖存储侧原子性，
 * 而不是进程内锁）；key 以租户隔离，跨租户 token 天然不可见。
 */
public interface TrustedActionStore {

    /** 写入挂起动作；ttl 由调用方给定，存储侧负责到期自动回收。 */
    void save(String tenantId, String token, PendingTrustedAction pending, Duration ttl);

    /**
     * 原子取出并删除挂起动作：存在且未过期返回内容，否则返回 empty。
     * 重复消费、跨租户消费、过期消费均返回 empty。
     */
    Optional<PendingTrustedAction> consume(String tenantId, String token);
}
