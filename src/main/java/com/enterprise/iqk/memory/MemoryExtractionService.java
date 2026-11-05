package com.enterprise.iqk.memory;

import com.enterprise.iqk.config.properties.MemoryProperties;
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
 * 5. 纠偏消解（冲突仲裁的写侧闭环）——提取器三分类 add/correct/none：
 *    模型判定用户"纠正了既有画像"时，被纠正的旧画像软失效（INVALIDATE 事件
 *    留痕、退出召回池）后再写入新画像，杜绝新旧矛盾画像共存；摘抄对不上
 *    既有列表时保守退化为 add，绝不盲删。
 * 6. 双重去重 —— 与既有 long 记忆先做规范化包含比对，再做向量语义比对
 *    （换措辞的重复画像不再入库；语义比对只消费已缓存向量的记录，存量
 *    无向量记录回退文本判重，避免每次提取重复 embedding 老数据）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryExtractionService {

    /** 提取结果 JSON 的系统提示词：三分类（新增画像/纠正既有画像/都不是），要求模型只输出严格 JSON。 */
    private static final String SYSTEM_PROMPT = """
            你是记忆分类器。用户消息开头的"既有画像记忆"列出了该用户当前已沉淀的画像（可能已过时）。
            判断这轮对话属于哪种情况，只输出 JSON，不要输出任何其他内容：
            {"action": "add|correct|none", "memory": "用第三人称一句话概括", "supersedes": "被纠正的既有画像原文"}
            规则：
            - action=add：出现了新的稳定用户画像信息（长期有效的偏好、背景、身份、目标，
              例如：技术栈偏好、学习目标、职业背景、沟通偏好）；
            - action=correct：用户明确纠正或更新了"既有画像记忆"里列出的某一条
              （如"不对，我早改用 xx 了""现在主要做 yy"）；
            - action=none：一次性的问题、临时需求、与用户本人无关的内容、不确定；
            - 不确定时一律 none（错误成本不对称：误存画像会污染上下文）；
            - memory 只在 add/correct 时填写；
            - supersedes 只在 correct 时填写，且必须逐字摘自"既有画像记忆"里的某一条。
            """;

    /** 画像记忆单条截断上限（字符）。 */
    private static final int MAX_MEMORY_CHARS = 200;
    /** 去重时回扫的既有 long 记忆条数上限。 */
    private static final int LONG_MEMORY_SCAN_LIMIT = 20;
    /** 提示词里最多携带的既有画像条数（控 token，超过部分不参与 correct 匹配）。 */
    private static final int MAX_EXISTING_IN_PROMPT = 20;
    /** 提取调用的端点标识（模型路由与成本记账共用）。 */
    private static final String EXTRACTION_ENDPOINT = "memory_extraction";

    private final MemoryService memoryService;
    private final ChatClient chatClient;
    private final ModelRouter modelRouter;
    private final TenantCostService tenantCostService;
    private final ObjectMapper objectMapper;
    private final MemoryEmbeddingHelper embeddingHelper;
    private final MemoryProperties memoryProperties;

    /** 提取线程池：守护线程，不阻碍 JVM 退出。 */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "memory-extract");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 对话主链路只调这个方法：把提取任务丢进线程池立即返回，
     * 保证记忆提取永远不会拖慢对话响应。
     * userKey 是记忆的 user 键（认证主体，匿名回落 chatId）——画像按人存，
     * 跨会话可召回；chatId 仅用于路由分桶与 source 溯源。
     */
    public void submitAsync(String tenantId, String userKey, String chatId, String prompt, String answer) {
        if (!StringUtils.hasText(prompt)) {
            return;
        }
        try {
            executor.execute(() -> extract(tenantId, userKey, chatId, prompt, answer));
        } catch (Exception ex) {
            // 线程池饱和或已关闭时直接放弃，画像提取是尽力而为
            log.warn("画像提取任务提交失败（不影响对话）: chatId={}, reason={}", chatId, ex.toString());
        }
    }

    /**
     * 同步提取逻辑（包内可见，便于直接测试）：
     * 取既有画像 -> 模型三分类（add/correct/none）-> JSON 解析 ->
     * add 走双重去重后写入；correct 软失效被纠正的旧画像再写入新画像；
     * 全程 try/catch，任何一步失败都按"不升级"收场。
     */
    void extract(String tenantId, String userKey, String chatId, String prompt, String answer) {
        try {
            ModelRouter.ModelRouteDecision decision = modelRouter.resolve(
                    "economy", EXTRACTION_ENDPOINT, tenantId, chatId);
            List<MemoryItemRecord> existingLongs =
                    memoryService.queryLongMemory(tenantId, userKey, LONG_MEMORY_SCAN_LIMIT);
            String userPrompt = buildUserPrompt(existingLongs, prompt, answer);
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

            tenantCostService.recordUsage(tenantId, userKey, decision.costTier(), inputTokens,
                    tenantCostService.estimateTokens(raw), EXTRACTION_ENDPOINT);

            ExtractionVerdict verdict = parseVerdict(raw);
            if (verdict == null || "none".equals(verdict.action()) || !StringUtils.hasText(verdict.memory())) {
                // 解析失败或明确无关：默认不升级（错误成本不对称）
                return;
            }
            String memory = truncate(verdict.memory(), MAX_MEMORY_CHARS);
            if ("correct".equals(verdict.action())) {
                applyCorrection(tenantId, userKey, chatId, existingLongs, memory, verdict.supersedes());
                return;
            }
            saveProfileIfNew(tenantId, userKey, chatId, existingLongs, memory);
        } catch (Exception ex) {
            log.warn("画像提取失败（不影响对话）: chatId={}, reason={}", chatId, ex.toString());
        }
    }

    /** add 路径：与既有画像先做规范化包含比对，再做向量语义比对，重复不落库。 */
    private void saveProfileIfNew(String tenantId, String userKey, String chatId,
                                  List<MemoryItemRecord> existingLongs, String memory) {
        if (isDuplicate(existingLongs, memory) || isSemanticDuplicate(existingLongs, memory)) {
            return;
        }
        memoryService.saveLongMemory(tenantId, userKey, "画像: " + memory,
                "extract:chat:" + chatId);
    }

    /**
     * correct 路径（冲突消解）：模型摘抄的"被纠正画像"与既有列表做规范化包含匹配——
     * 命中则软失效旧画像（INVALIDATE 事件留痕、退出召回池）再写入新画像；
     * 摘抄对不上号（模型幻觉/列表已变）时保守退化为 add 路径，绝不盲删。
     */
    private void applyCorrection(String tenantId, String userKey, String chatId,
                                 List<MemoryItemRecord> existingLongs, String memory, String supersedes) {
        MemoryItemRecord superseded = findSuperseded(existingLongs, supersedes);
        if (superseded == null) {
            log.warn("纠偏摘抄未命中既有画像（退化为新增）: chatId={}, supersedes={}",
                    chatId, truncate(supersedes == null ? "" : supersedes, 80));
            saveProfileIfNew(tenantId, userKey, chatId, existingLongs, memory);
            return;
        }
        boolean invalidated = memoryService.softInvalidateMemory(tenantId, superseded.getMemoryId(),
                "superseded by user correction: chat=" + chatId);
        if (!invalidated) {
            // 软失效失败（并发已失效/租户不符）：新画像仍写入，旧画像留存由后续纠偏兜底
            log.warn("纠偏软失效未生效（新画像照常写入）: chatId={}, memoryId={}",
                    chatId, superseded.getMemoryId());
        }
        memoryService.saveLongMemory(tenantId, userKey, "画像: " + memory,
                "extract:chat:" + chatId);
    }

    /** 摘抄文本与既有画像做规范化互含匹配（都去掉空白后互为包含即视为同一条）。 */
    private MemoryItemRecord findSuperseded(List<MemoryItemRecord> existingLongs, String supersedes) {
        if (!StringUtils.hasText(supersedes) || existingLongs == null) {
            return null;
        }
        String needle = normalize(supersedes);
        for (MemoryItemRecord item : existingLongs) {
            String content = normalize(item.getContent());
            if (content.isEmpty()) {
                continue;
            }
            if (content.contains(needle) || needle.contains(content)) {
                return item;
            }
        }
        return null;
    }

    /** 拼接提取用的用户消息：既有画像列表在前（模型据此判 correct），问答各截断 400 字符。 */
    private String buildUserPrompt(List<MemoryItemRecord> existingLongs, String prompt, String answer) {
        StringBuilder userPrompt = new StringBuilder("既有画像记忆：\n");
        if (existingLongs == null || existingLongs.isEmpty()) {
            userPrompt.append("（暂无）\n");
        } else {
            int shown = 0;
            for (MemoryItemRecord item : existingLongs) {
                if (shown >= MAX_EXISTING_IN_PROMPT) {
                    break;
                }
                String content = item.getContent() == null ? "" : item.getContent();
                if (!StringUtils.hasText(content)) {
                    continue;
                }
                userPrompt.append("- ").append(truncate(content, 120)).append("\n");
                shown++;
            }
            if (shown == 0) {
                userPrompt.append("（暂无）\n");
            }
        }
        userPrompt.append("\n用户提问: ").append(truncate(prompt, 400));
        if (StringUtils.hasText(answer)) {
            userPrompt.append("\n系统回答: ").append(truncate(answer, 400));
        }
        return userPrompt.toString();
    }

    /**
     * 解析模型输出为三分类判定。容忍 ```json 代码块包裹；兼容旧格式
     * （{"isProfile":bool,"memory":str} 映射为 add/none）；未知 action 归 none；
     * 任何解析失败返回 null，调用方按"不升级"处理。
     */
    ExtractionVerdict parseVerdict(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(stripCodeFence(raw));
            String memory = node.path("memory").asText(null);
            if (node.has("action")) {
                String action = node.path("action").asText("");
                return switch (action) {
                    case "add", "correct", "none" ->
                            new ExtractionVerdict(action, memory, node.path("supersedes").asText(null));
                    default -> new ExtractionVerdict("none", memory, null);
                };
            }
            // 旧格式兼容：isProfile=true 视为 add
            if (node.has("isProfile")) {
                boolean isProfile = node.path("isProfile").asBoolean(false);
                return new ExtractionVerdict(isProfile ? "add" : "none", memory, null);
            }
            return null;
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
     * 保守的文本级去重，语义近似（换措辞）由 {@link #isSemanticDuplicate} 兜住。
     */
    private boolean isDuplicate(List<MemoryItemRecord> existing, String memory) {
        String normalized = normalize(memory);
        return existing != null && existing.stream()
                .map(item -> normalize(item.getContent()))
                .anyMatch(old -> old.contains(normalized) || normalized.contains(old));
    }

    /**
     * 语义去重：候选画像与任一既有 long 记忆的余弦相似度达阈值即视为重复。
     * 只比对已缓存向量（embedding_json）的既有记录——候选只需一次 embedding，
     * 老数据不为判重反复计算；语义开关关闭或任何一步失败返回 false（不拦写入）。
     */
    boolean isSemanticDuplicate(List<MemoryItemRecord> existing, String memory) {
        if (!memoryProperties.isSemanticEnabled() || existing == null || existing.isEmpty()) {
            return false;
        }
        try {
            float[] candidate = embeddingHelper.embed(memory);
            if (candidate == null) {
                return false;
            }
            for (MemoryItemRecord item : existing) {
                float[] vector = MemoryEmbeddingHelper.fromJson(item.getEmbeddingJson());
                if (vector == null) {
                    continue;
                }
                if (MemoryEmbeddingHelper.cosine(candidate, vector)
                        >= memoryProperties.getSemanticDedupThreshold()) {
                    return true;
                }
            }
        } catch (Exception ex) {
            log.warn("画像语义去重失败（回退文本判重）: reason={}", ex.toString());
        }
        return false;
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
    /** 提取判定结果：action ∈ {add, correct, none}；correct 时 supersedes 为被纠正画像的摘抄原文。 */
    record ExtractionVerdict(String action, String memory, String supersedes) {
    }
}
