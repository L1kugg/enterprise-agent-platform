package com.enterprise.iqk.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Persists a per-turn short-term memory once a chat stream completes.
 *
 * Short memory is conversation-scoped by design: the platform has no
 * per-user auth in the chat path, so the chatId acts as the memory's user
 * key and the source field records the conversation it belongs to.
 * Persistence is best-effort — a memory failure must never break the chat
 * response or the cost recording that shares the same terminal hook.
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
