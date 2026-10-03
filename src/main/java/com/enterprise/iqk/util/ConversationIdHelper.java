package com.enterprise.iqk.util;

import org.springframework.util.StringUtils;

/**
 * 会话 ID 派生工具：以 type::chatId 形式组合出全局唯一的 conversationId，
 * 供 chat/react/eval/memory-extract 等链路间做上下文隔离，避免消息互相串扰。
 * type 与 chatId 任一为空白即抛参数异常，防止生成非法会话键。
 */
public final class ConversationIdHelper {
    /** 类型前缀与 chatId 之间的分隔符 */
    private static final String SEPARATOR = "::";

    private ConversationIdHelper() {
    }

    /** 拼接 type::chatId；任一为空白抛 IllegalArgumentException。 */
    public static String build(String type, String chatId) {
        if (!StringUtils.hasText(type) || !StringUtils.hasText(chatId)) {
            throw new IllegalArgumentException("type 和 chatId 不能为空");
        }
        return type + SEPARATOR + chatId;
    }

    /** 从 conversationId 剥掉 type 前缀还原 chatId；无分隔符时原样返回。 */
    public static String extractChatId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return conversationId;
        }
        int index = conversationId.indexOf(SEPARATOR);
        return index < 0 ? conversationId : conversationId.substring(index + SEPARATOR.length());
    }
}
