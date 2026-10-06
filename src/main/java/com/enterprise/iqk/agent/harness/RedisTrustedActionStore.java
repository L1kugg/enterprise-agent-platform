package com.enterprise.iqk.agent.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 实现：多实例部署的受信动作存储后端。
 * 写入用 SET ... EX TTL 到期自动回收；消费用 GETDEL 原子取出并删除，
 * 同一 token 在任意实例上只能被消费一次，preview 与 execute 落在不同节点也能工作。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.agent-harness.trusted-action",
        name = "store", havingValue = "redis")
public class RedisTrustedActionStore implements TrustedActionStore {

    private static final String KEY_PREFIX = "trusted-action:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void save(String tenantId, String token, PendingTrustedAction pending, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key(tenantId, token),
                    objectMapper.writeValueAsString(pending), ttl);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize trusted action", ex);
        }
    }

    @Override
    public Optional<PendingTrustedAction> consume(String tenantId, String token) {
        String json = redisTemplate.opsForValue().getAndDelete(key(tenantId, token));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(json, PendingTrustedAction.class));
        } catch (JsonProcessingException ex) {
            log.warn("failed to deserialize trusted action, treating token as consumed");
            return Optional.empty();
        }
    }

    private String key(String tenantId, String token) {
        return KEY_PREFIX + tenantId + ":" + token;
    }
}
