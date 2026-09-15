package com.enterprise.iqk.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Persists the conclusion of a completed agent task as task-scoped memory.
 *
 * Task memory keeps intermediate conclusions (research findings, resolved
 * support cases) bound to their taskId with a 30-day TTL, so a follow-up
 * request can recall what an earlier task concluded. The chatId is stored
 * as the user key when the task originated from a conversation. Writes are
 * best-effort — a memory failure must never affect task completion.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskConclusionMemoryRecorder {

    private static final int MAX_USER_INPUT_CHARS = 160;
    private static final int MAX_CONCLUSION_CHARS = 600;

    private final MemoryService memoryService;

    public void recordConclusion(String tenantId, String taskId, String taskType,
                                 String userInput, String conclusion, String chatId) {
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(conclusion)) {
            return;
        }
        try {
            memoryService.saveTaskMemory(tenantId, chatId, buildContent(taskType, userInput, conclusion), taskId);
        } catch (Exception ex) {
            log.warn("task memory persistence failed: taskId={}, reason={}", taskId, ex.toString());
        }
    }

    private String buildContent(String taskType, String userInput, String conclusion) {
        StringBuilder content = new StringBuilder();
        if (StringUtils.hasText(taskType)) {
            content.append("[").append(taskType).append("] ");
        }
        if (StringUtils.hasText(userInput)) {
            content.append("目标: ").append(truncate(userInput, MAX_USER_INPUT_CHARS)).append('\n');
        }
        content.append("结论: ").append(truncate(conclusion, MAX_CONCLUSION_CHARS));
        return content.toString();
    }

    private String truncate(String text, int maxChars) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "…";
    }
}
