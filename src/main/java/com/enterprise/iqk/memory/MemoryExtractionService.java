package com.enterprise.iqk.memory;

import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.service.TenantCostService;
import com.enterprise.iqk.util.ConversationIdHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * 对话轮次结束后的画像提取服务：判断本轮对话是否包含稳定的用户画像信息
 * （偏好、背景、目标），命中才升级为 long 记忆。
 *
 * 设计要点：
 * 1. 错误成本不对称 —— short 误存成 long 会永久污染上下文，long 误存成 short
 *    只会在 24 小时后自然消失。因此只有模型明确判定 isProfile=true 且 JSON
 *    解析成功时才写入 long，任何失败一律按"不升级"处理；
 * 2. 异步执行 —— 提取走独立守护线程池，排队、失败、超时都不阻塞对话主链路；
 * 3. 成本受控 —— 提取走 economy 档模型，调用计入租户成本（只记账不拦截：
 *    画像提取是可选增强，不应挤占用户问答的预算名额）；
 * 4. 隔离会话 —— 提取调用使用独立的 memory-extract 会话 ID，避免提取轮次
 *    写进用户可见的对话历史；
 * 5. 简单去重 —— 与既有 long 记忆做规范化后的包含比对，语义级去重留作演进。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryExtractionService {

    /** 提取结果 JSON 的系统提示词，要求模型只输出严格 JSON。 */
    private static final String SYSTEM_PROMPT = """
            你是记忆分类器。判断下面这轮对话中是否出现"稳定的用户画像信息"：
            用户长期有效的偏好、背景、身份、目标（例如：技术栈偏好、学习目标、
            职业背景、沟通偏好）。
            只输出 JSON，不要输出任何其他内容：
            {"isProfile": true/false, "memory": "用第三人称一句话概括画像信息"}
            规则：
            - 一次性的问题、临时需求、与用户本人无关的内容，isProfile 为 false；
            - 不确定时 isProfile 为 false；
            - memory 只在 isProfile 为 true 时填写。
            """;

    /** 画像记忆单条截断上限（字符）。 */
    private static final int MAX_MEMORY_CHARS = 200;
    /** 去重时回扫的既有 long 记忆条数上限。 */
    private static final int LONG_MEMORY_SCAN_LIMIT = 20;
    /** 提取调用的端点标识（模型路由与成本记账共用）。 */
    private static final String EXTRACTION_ENDPOINT = "memory_extraction";

    private final MemoryService memoryService;
    private final ChatClient chatClient;
    private final ModelRouter modelRouter;
    private final TenantCostService tenantCostService;
    private final ObjectMapper objectMapper;

    /** 提取线程池：守护线程，不阻碍 JVM 退出。 */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "memory-extract");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 对话主链路只调这个方法：把提取任务丢进线程池立即返回，
     * 保证记忆提取永远不会拖慢对话响应。
     */
    public void submitAsync(String tenantId, String chatId, String prompt, String answer) {
        if (!StringUtils.hasText(prompt)) {
            return;
        }
        try {
            executor.execute(() -> extract(tenantId, chatId, prompt, answer));
        } catch (Exception ex) {
            // 线程池饱和或已关闭时直接放弃，画像提取是尽力而为
            log.warn("画像提取任务提交失败（不影响对话）: chatId={}, reason={}", chatId, ex.toString());
        }
    }

    /**
     * 同步提取逻辑（包内可见，便于直接测试）：
     * 模型判定 -> JSON 解析 -> 去重 -> 写入 long 记忆 -> 记账。
     * 全程 try/catch，任何一步失败都按"不升级"收场。
     */
    void extract(String tenantId, String chatId, String prompt, String answer) {
        try {
            ModelRouter.ModelRouteDecision decision = modelRouter.resolve(
                    "economy", EXTRACTION_ENDPOINT, tenantId, chatId);
            String userPrompt = buildUserPrompt(prompt, answer);
            long inputTokens = tenantCostService.estimateTokens(SYSTEM_PROMPT + userPrompt);

            String raw = chatClient.prompt()
                    .options(ChatOptions.builder()
                            .model(decision.model())
                            .temperature(0.0)
                            .build())
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    // 独立会话 ID：提取轮次不进入用户可见对话历史
                    .advisors(a -> a.param(CONVERSATION_ID,
                            ConversationIdHelper.build("memory-extract", chatId)))
                    .call()
                    .content();

            tenantCostService.recordUsage(tenantId, decision.costTier(), inputTokens,
                    tenantCostService.estimateTokens(raw), EXTRACTION_ENDPOINT);

            ExtractionVerdict verdict = parseVerdict(raw);
            if (verdict == null || !verdict.isProfile() || !StringUtils.hasText(verdict.memory())) {
                // 解析失败或明确不是画像：默认不升级（错误成本不对称）
                return;
            }
            String memory = truncate(verdict.memory(), MAX_MEMORY_CHARS);
            if (isDuplicate(memoryService.queryLongMemory(tenantId, chatId, LONG_MEMORY_SCAN_LIMIT), memory)) {
                return;
            }
            memoryService.saveLongMemory(tenantId, chatId, "画像: " + memory,
                    "extract:chat:" + chatId);
        } catch (Exception ex) {
            log.warn("画像提取失败（不影响对话）: chatId={}, reason={}", chatId, ex.toString());
        }
    }

    /** 拼接提取用的用户消息：问答各截断 400 字符，回答为空时只送提问。 */
    private String buildUserPrompt(String prompt, String answer) {
        StringBuilder userPrompt = new StringBuilder("用户提问: ").append(truncate(prompt, 400));
        if (StringUtils.hasText(answer)) {
            userPrompt.append("\n系统回答: ").append(truncate(answer, 400));
        }
        return userPrompt.toString();
    }

    /**
     * 解析模型输出。容忍 ```json 代码块包裹；任何解析失败返回 null，
     * 调用方按"不升级"处理。
     */
    ExtractionVerdict parseVerdict(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(stripCodeFence(raw));
            if (!node.has("isProfile")) {
                return null;
            }
            return new ExtractionVerdict(
                    node.path("isProfile").asBoolean(false),
                    node.path("memory").asText(null));
        } catch (Exception ex) {
            return null;
        }
    }

    /** 去掉模型可能包裹的 markdown 代码块围栏。 */
    private String stripCodeFence(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstLineBreak = trimmed.indexOf('\n');
            if (firstLineBreak > 0) {
                trimmed = trimmed.substring(firstLineBreak + 1);
            }
            int closingFence = trimmed.lastIndexOf("```");
            if (closingFence >= 0) {
                trimmed = trimmed.substring(0, closingFence);
            }
        }
        return trimmed.trim();
    }

    /**
     * 规范化后的双向包含比对：新记忆与任一既有记忆互为包含即视为重复。
     * 这是保守的文本级去重，语义级去重（向量相似度）留作后续演进。
     */
    private boolean isDuplicate(List<MemoryItemRecord> existing, String memory) {
        String normalized = normalize(memory);
        return existing != null && existing.stream()
                .map(item -> normalize(item.getContent()))
                .anyMatch(old -> old.contains(normalized) || normalized.contains(old));
    }

    /** 规范化用于去重比对的文本：去除全部空白。 */
    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").trim();
    }

    /** 空白规范化为单空格后按上限截断，超长补省略号。 */
    private String truncate(String text, int maxChars) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "…";
    }

    /** 应用关闭时优雅停机：最多等待 5 秒，超时或被中断则强制关闭。 */
    @PreDestroy
    void shutdownExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 提取判定结果。 */
    record ExtractionVerdict(boolean isProfile, String memory) {
    }
}
