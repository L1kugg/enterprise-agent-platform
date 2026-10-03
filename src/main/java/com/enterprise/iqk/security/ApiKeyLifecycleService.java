package com.enterprise.iqk.security;

import com.enterprise.iqk.config.properties.SecurityProperties;
import com.enterprise.iqk.domain.ApiKeyRecord;
import com.enterprise.iqk.mapper.ApiKeyMapper;
import com.enterprise.iqk.util.HashUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApiKeyLifecycleService {
    private final ApiKeyMapper apiKeyMapper;
    private final SecurityProperties securityProperties;

    public ApiKeyIssueResult issue(String keyName, String roleName, String tenantId) {
        String normalizedTenant = normalizeTenant(tenantId);
        ApiKeyRecord active = apiKeyMapper.findActiveByKeyName(keyName, normalizedTenant);
        if (active != null) {
            throw new IllegalArgumentException("该 Key 名称已存在且仍在有效期");
        }
        String raw = "ak-" + UUID.randomUUID().toString().replace("-", "");
        return provision(raw, keyName, roleName, normalizedTenant);
    }

    /**
     * 供操作员提供的凭据（bootstrap / contract 技术栈）进行开通。
     * 幂等操作：同名且仍处于活跃状态的 key 保持不变；
     * 与其哈希相同的已吊销记录会被重新启用，而不是与
     * UNIQUE key_hash 约束冲突。
     */
    public ApiKeyIssueResult provision(String rawKey, String keyName, String roleName, String tenantId) {
        String normalizedTenant = normalizeTenant(tenantId);
        ApiKeyRecord active = apiKeyMapper.findActiveByKeyName(keyName, normalizedTenant);
        if (active != null) {
            return new ApiKeyIssueResult(null, keyName, normalizedTenant, active.getExpiresAt());
        }
        String rawHash = HashUtils.sha256Hex(rawKey);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusDays(Math.max(1, securityProperties.getApiKeyExpireDays()));
        // 依据 key_hash 而非 key_name 重新启用：已吊销的记录可能仍保留
        // 最初的种子 key_name（V7 'demo-admin-key-2026'），而操作员开通时
        // 传入的是另一个 APP_BOOTSTRAP_KEY_NAME，正是 UNIQUE key_hash
        // 约束迫使我们定位到那条记录。
        ApiKeyRecord latest = apiKeyMapper.findByKeyHash(rawHash);
        if (latest != null) {
            // 使用显式的 revive SQL：MyBatis-Plus 的 updateById 会跳过 null 字段，
            // 导致 revoked_at / revoked_reason 保持原值，重新启用的 key
            // 将永远无法再匹配 findActive 查询。
            apiKeyMapper.revive(latest.getId(), keyName, normalizedTenant, roleName, expiresAt, now);
            return new ApiKeyIssueResult(rawKey, keyName, normalizedTenant, expiresAt);
        }
        ApiKeyRecord record = ApiKeyRecord.builder()
                .keyHash(rawHash)
                .keyName(keyName)
                .tenantId(normalizedTenant)
                .roleName(roleName)
                .enabled(1)
                .expiresAt(expiresAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
        apiKeyMapper.insert(record);
        return new ApiKeyIssueResult(rawKey, keyName, normalizedTenant, expiresAt);
    }

    public ApiKeyIssueResult rotate(String keyName, String reason, String tenantId) {
        String normalizedTenant = normalizeTenant(tenantId);
        ApiKeyRecord old = apiKeyMapper.findActiveByKeyName(keyName, normalizedTenant);
        if (old == null) {
            throw new IllegalArgumentException("未找到对应的有效 API Key");
        }
        apiKeyMapper.revoke(old.getId(), LocalDateTime.now(), reason, LocalDateTime.now());
        ApiKeyIssueResult issued = issue(keyName, old.getRoleName(), normalizedTenant);
        ApiKeyRecord newer = apiKeyMapper.findActiveByKeyName(keyName, normalizedTenant);
        if (newer != null) {
            newer.setRotatedFromId(old.getId());
            newer.setUpdatedAt(LocalDateTime.now());
            apiKeyMapper.updateById(newer);
        }
        return issued;
    }

    public void revoke(String keyName, String reason, String tenantId) {
        String normalizedTenant = normalizeTenant(tenantId);
        ApiKeyRecord record = apiKeyMapper.findActiveByKeyName(keyName, normalizedTenant);
        if (record == null) {
            throw new IllegalArgumentException("未找到对应的有效 API Key");
        }
        apiKeyMapper.revoke(record.getId(), LocalDateTime.now(), reason, LocalDateTime.now());
    }

    private String normalizeTenant(String tenantId) {
        return TenantContext.normalize(tenantId);
    }

    public record ApiKeyIssueResult(String rawApiKey, String keyName, String tenantId, LocalDateTime expiresAt) {}
}
