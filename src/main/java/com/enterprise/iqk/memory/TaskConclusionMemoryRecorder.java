package com.enterprise.iqk.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 任务完成后，把任务结论写入 task 记忆。
 *
 * task 记忆保存中间结论（研究发现、客服处理方案），绑定 taskId、
 * 30 天过期，后续请求可以召回早前任务的结论。
 * 任务来源于会话时，chatId 作为 user 键存储。
 * 写入是尽力而为的 —— 记忆失败绝不能影响任务完成本身。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskConclusionMemoryRecorder {

    /** 目标（用户输入）侧截断上限（字符）。 */
    private static final int MAX_USER_INPUT_CHARS = 160;
    /** 结论侧截断上限（字符）。 */
    private static final int MAX_CONCLUSION_CHARS = 600;

    private final MemoryService memoryService;

    /** 把任务结论写入 task 记忆（仅在 DONE 时由调用方触发，FAILED 不写）；缺 taskId 或结论为空白时静默跳过，失败只告警不抛出。 */
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

    /** 拼接 "[类型] 目标: .../结论: ..." 格式的记忆内容，类型与目标缺失时省略对应段。 */
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

    /** 空白规范化为单空格后按上限截断，超长补省略号。 */
    private String truncate(String text, int maxChars) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "…";
    }
}
