package com.enterprise.iqk.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话记忆窗口治理（PROJECT_DOC §18.2 第一步）：控制进模型的历史规模，
 * 不影响 conversation 表的全量持久化（审计/回放保留完整数据）。
 */
@Data
@ConfigurationProperties(prefix = "app.chat.memory")
public class ChatMemoryProperties {

    /** 读取窗口：进模型的最大历史消息条数（含 user/assistant），默认 20 条 ≈ 10 轮。 */
    private int maxMessages = 20;

    /**
     * 历史 Token 预算：条数窗口内再按 Token 裁剪，从最旧整轮开始丢弃，
     * 保底保留最近 1 整轮。估算口径与 TenantCostService 一致（码点数/4 向上取整）。
     */
    private long maxHistoryTokens = 6_000;
}
