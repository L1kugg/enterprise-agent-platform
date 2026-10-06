package com.enterprise.iqk.agent.harness;

import java.time.Instant;

/**
 * 挂起中的受信动作：preview 阶段生成、execute 阶段消费。
 * 独立成顶层记录以便内存 / Redis 两套存储实现共用同一序列化载体。
 */
public record PendingTrustedAction(AgentAction action, Instant expiresAt) {
}
