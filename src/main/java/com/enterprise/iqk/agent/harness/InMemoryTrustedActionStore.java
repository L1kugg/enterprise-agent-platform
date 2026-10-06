package com.enterprise.iqk.agent.harness;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单进程内存实现：本地开发与测试默认后端。
 * 过期条目在访问时惰性回收（consume 未命中即弃），进程内存无需定时清扫。
 * 多实例部署应切换到 Redis 后端（app.agent-harness.trusted-action.store=redis）。
 */
@Component
@ConditionalOnProperty(prefix = "app.agent-harness.trusted-action",
        name = "store", havingValue = "memory", matchIfMissing = true)
public class InMemoryTrustedActionStore implements TrustedActionStore {

    private final Map<String, PendingTrustedAction> store = new ConcurrentHashMap<>();

    @Override
    public void save(String tenantId, String token, PendingTrustedAction pending, Duration ttl) {
        store.put(key(tenantId, token), pending);
    }

    @Override
    public Optional<PendingTrustedAction> consume(String tenantId, String token) {
        String key = key(tenantId, token);
        PendingTrustedAction pending = store.get(key);
        if (pending == null) {
            return Optional.empty();
        }
        if (pending.expiresAt().isBefore(Instant.now())) {
            store.remove(key, pending);
            return Optional.empty();
        }
        // remove(key, value) 保证只有第一个到达的消费者能拿到动作
        return store.remove(key, pending)
                ? Optional.of(pending)
                : Optional.empty();
    }

    private String key(String tenantId, String token) {
        return tenantId + ":" + token;
    }
}
