package com.enterprise.iqk.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 对话流正常结束后，把本轮问答摘要写入会话级 short 记忆。
 *
 * short 记忆按会话（chatId）作用域存储：平台对话链路没有独立的用户身份，
 * 因此 chatId 同时充当记忆的 user 键，source 字段记录所属会话。
 * 写入是尽力而为的 —— 记忆失败绝不能影响对话响应，
 * 也不能影响共享同一个流结束钩子的计费逻辑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatTurnMemoryRecorder {

    private static final int MAX_PROMPT_CHARS = 200;
    private static final int MAX_ANSWER_CHARS = 400;

    private final MemoryService memoryService;

    public void recordTurn(String tenantId, String chatId, String prompt, String answer) {
        if (!StringUtils.hasText(prompt) || !StringUtils.hasText(answer)) {
            return;
        }
        try {
            String content = "Q: " + truncate(prompt, MAX_PROMPT_CHARS)
                    + "\nA: " + truncate(answer, MAX_ANSWER_CHARS);
            memoryService.saveShortMemory(tenantId, chatId, content, "chat:" + chatId);
        } catch (Exception ex) {
            log.warn("short memory persistence failed: chatId={}, reason={}", chatId, ex.toString());
        }
    }

    private String truncate(String text, int maxChars) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "…";
    }
}
