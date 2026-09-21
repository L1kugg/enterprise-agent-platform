package com.enterprise.iqk.controller;

import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.memory.ChatTurnMemoryRecorder;
import com.enterprise.iqk.memory.MemoryExtractionService;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.repository.ChatHistoryRepository;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.service.TenantCostService;
import com.enterprise.iqk.util.ConversationIdHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.Media;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * 与平台其他部分一样，将 /ai/chat 和 /ai/chat/stream 接入
 * TenantCostService。若不这样做，持有 PERM_CHAT_WRITE 的调用者
 * 可以无限发起 /ai/chat 请求并消耗 LLM token，却从不计入租户的
 * 月度预算，使 cost_governance.enabled = true 配置形同虚设。
 */
@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class ChatController {

    private final ChatClient chatClient;
    private final ModelRouter modelRouter;
    private final TenantCostService tenantCostService;

    private final ChatHistoryRepository chatHistoryRepository;
    private final ChatTurnMemoryRecorder chatTurnMemoryRecorder;
    private final MemoryExtractionService memoryExtractionService;
    /** 记忆子系统：召回跨会话记忆（long/fact）注入生成上下文（读侧闭环）。 */
    private final MemoryService memoryService;

    /** POST /ai/chat 聊天入口（text/html 流式响应）：保存会话后按有无附件分流纯文本/多模态链路。 */
    @PostMapping(value = "/chat", produces = "text/html;charset=utf-8")
    public Flux<String> chat(
            @RequestParam("prompt") String prompt,
            @RequestParam("chatId") String chatId,
            @RequestParam(value = "modelProfile", required = false) String modelProfile,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        // 1.保存会话id
        chatHistoryRepository.save("chat", chatId);
        String conversationId = ConversationIdHelper.build("chat", chatId);
        // 2.召回跨会话记忆（long/fact），追加到发给模型的用户消息末尾。
        // 本链路已挂 ChatMemory advisor，会话内近况由 advisor 保真注入，
        // 因此只取跨会话视图，避免 short 摘要与原文双份进 prompt。
        String tenantId = TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
        String enrichedPrompt = prompt + recallMemorySection(tenantId, chatId);
        // 3.请求模型
        if (files == null || files.isEmpty()) {
            // 没有附件，纯文本聊天
            return textChat(prompt, enrichedPrompt, conversationId, modelProfile, chatId);
        } else {
            // 有附件，多模态聊天
            return multiModalChat(prompt, enrichedPrompt, conversationId, files, modelProfile, chatId);
        }
    }

    /** POST /ai/chat/stream 聊天入口（SSE 流式响应）：内部直接复用 /ai/chat。 */
    @RequestMapping(value = "/chat/stream", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(
            @RequestParam("prompt") String prompt,
            @RequestParam("chatId") String chatId,
            @RequestParam(value = "modelProfile", required = false) String modelProfile,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        return chat(prompt, chatId, modelProfile, files);
    }

    /** 多模态聊天：把附件转成 Media 附到用户消息上，再走统一跟踪流（文本部分用增强后 prompt）。 */
    private Flux<String> multiModalChat(String prompt,
                                        String enrichedPrompt,
                                        String conversationId,
                                        List<MultipartFile> files,
                                        String modelProfile,
                                        String chatId) {
        List<Media> mediaList = files.stream().map(f -> {
            // 若 multipart 上传未显式携带 Content-Type 头，Spring 的
            // MultipartFile.getContentType() 会返回 null；将其传给
            // MimeType.valueOf 会抛出 NPE。这里回退为 application/
            // octet-stream，使模型仍能拿到可用的 MimeType，错误也
            // 保持为干净的 4xx 而非 500。
            String contentType = f.getContentType();
            MimeType mime = StringUtils.hasText(contentType)
                    ? MimeType.valueOf(contentType)
                    : MediaType.APPLICATION_OCTET_STREAM;
            return new Media(mime, f.getResource());
        }).toList();

        return trackedChatStream(
                prompt, enrichedPrompt, modelProfile, chatId, conversationId, "chat",
                spec -> spec.user(t -> t.text(enrichedPrompt).media(mediaList.toArray(Media[]::new))));
    }

    /** 纯文本聊天：把增强后 prompt（原始问题 + 已知记忆段）作为用户消息走统一跟踪流。 */
    private Flux<String> textChat(String prompt,
                                  String enrichedPrompt,
                                  String conversationId,
                                  String modelProfile,
                                  String chatId) {
        return trackedChatStream(
                prompt, enrichedPrompt, modelProfile, chatId, conversationId, "chat",
                spec -> spec.user(enrichedPrompt));
    }

    /**
     * 通用的聊天流封装。上面两个调用方的差异仅在 .user(...) 调用，
     * 因此成本统计、advisor 与 stream() 的配置在此共享。
     * 若没有这个辅助方法，两个方法都会在 .user(prompt) 周围出现
     * 同样的六行代码，而这正是 SpotBugs DB_DUPLICATE_BRANCHES
     * 检查所标记的问题。
     */
    private Flux<String> trackedChatStream(String prompt,
                                          String enrichedPrompt,
                                          String modelProfile,
                                          String chatId,
                                          String conversationId,
                                          String endpointTag,
                                          Function<ChatClient.ChatClientRequestSpec, ChatClient.ChatClientRequestSpec> userCustomizer) {
        String tenantId = TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
        ModelRouter.ModelRouteDecision decision = modelRouter.resolve(modelProfile, "chat", tenantId, chatId);
        // 预算与记账按增强后 prompt 估算 —— 记忆段真实消耗了 token，
        // 不计入会造成租户用量低估（评测链路的记忆段就漏了这笔账）。
        long inputTokens = tenantCostService.estimateTokens(enrichedPrompt);
        tenantCostService.assertBudget(tenantId, decision.costTier(), inputTokens, 600);
        StringBuilder outputCollector = new StringBuilder();
        AtomicBoolean recorded = new AtomicBoolean(false);
        return userCustomizer.apply(chatClient.prompt()
                        .options(ChatOptions.builder().model(decision.model()).build()))
                .advisors(a -> a.param(CONVERSATION_ID, conversationId))
                .stream()
                .content()
                .doOnNext(outputCollector::append)
                .doFinally(signal -> {
                    if (recorded.compareAndSet(false, true)) {
                        long outputTokens = tenantCostService.estimateTokens(outputCollector.toString());
                        tenantCostService.recordUsage(tenantId, decision.costTier(), inputTokens, outputTokens, endpointTag);
                    }
                    // 本轮结束后用原始 prompt（非增强版）写入会话级 short 记忆
                    // 并异步提交画像提取（命中才升级 long）：若把注入的记忆段
                    // 也存回去，记忆会自我循环增殖。两者都是尽力而为：
                    // 异常在各自内部吞掉，绝不影响流收尾与计费。
                    if (signal == SignalType.ON_COMPLETE) {
                        chatTurnMemoryRecorder.recordTurn(tenantId, chatId, prompt, outputCollector.toString());
                        memoryExtractionService.submitAsync(tenantId, chatId, prompt, outputCollector.toString());
                    }
                });
    }

    /**
     * 召回跨会话记忆段（long/fact）：拼成 "\n\n已知记忆:\n..." 追加到用户消息末尾。
     * 尽力而为 —— 召回失败或空快照返回空串，绝不影响聊天主链路。
     */
    private String recallMemorySection(String tenantId, String chatId) {
        try {
            MemoryService.MemoryContextSnapshot snapshot = memoryService.buildContext(tenantId, chatId, false);
            if (snapshot == null || !StringUtils.hasText(snapshot.contextText())) {
                return "";
            }
            log.info("记忆注入 chat 链路: chatId={}, 注入 {} 字符", chatId, snapshot.contextText().length());
            return "\n\n已知记忆:\n" + snapshot.contextText().trim();
        } catch (Exception ex) {
            log.warn("记忆召回失败（不影响聊天链路）: chatId={}, reason={}", chatId, ex.toString());
            return "";
        }
    }
}
