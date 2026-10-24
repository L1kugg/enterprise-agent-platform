package com.enterprise.iqk.ingestion;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天内临时附件的进程内存存储：解析产物（纯文本）按 UUID 键短存，
 * 取用校验租户归属 + 过期惰性回收 + 定时清扫，绝不落库不入向量索引。
 * 多实例部署需演进为 Redis 共享存储（照 TrustedActionStore 的双后端先例）。
 */
@Component
public class ChatAttachmentStore {

    /** 单条附件记录：归属（租户+用户键）+ 解析文本 + 过期时刻。 */
    public record StoredAttachment(String tenantId, String userKey, String filename,
                                   String text, boolean truncated, Instant expiresAt) {
    }

    private final Map<String, StoredAttachment> store = new ConcurrentHashMap<>();

    public String save(StoredAttachment attachment) {
        String id = java.util.UUID.randomUUID().toString().replace("-", "");
        store.put(id, attachment);
        return id;
    }

    /** 取用：租户不匹配或已过期一律按不存在处理（惰性移除）。 */
    public Optional<StoredAttachment> get(String tenantId, String attachmentId) {
        StoredAttachment attachment = store.get(attachmentId);
        if (attachment == null) {
            return Optional.empty();
        }
        if (attachment.expiresAt().isBefore(Instant.now()) || !attachment.tenantId().equals(tenantId)) {
            store.remove(attachmentId, attachment);
            return Optional.empty();
        }
        return Optional.of(attachment);
    }

    public void remove(String attachmentId) {
        store.remove(attachmentId);
    }

    /** 每 5 分钟清扫过期项：附件文本体积可观，不能只靠惰性回收。 */
    @Scheduled(fixedDelay = 300_000)
    public void sweepExpired() {
        Instant now = Instant.now();
        store.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }
}
