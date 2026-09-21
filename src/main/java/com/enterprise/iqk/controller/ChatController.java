package com.enterprise.iqk.controller;

import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.memory.ChatTurnMemoryRecorder;
import com.enterprise.iqk.memory.MemoryExtractionService;
import com.enterprise.iqk.memory.MemoryInjectionAdvisor;
import com.enterprise.iqk.repository.ChatHistoryRepository;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.security.UserContext;
import com.enterprise.iqk.service.TenantCostService;
import com.enterprise.iqk.util.ConversationIdHelper;
import lombok.RequiredArgsConstructor;
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
 *
 * 记忆闭环：读侧经 MemoryInjectionAdvisor 在请求组装期注入"已知记忆"
 * system 消息（不落 ChatMemory、不随会话历史逐轮累积，每轮最新一份）；
 * 写侧在流结束后写 short 记忆并提交画像提取，均按认证主体
 * （userKey，匿名回落 chatId）存取 —— 画像跨会话可召回。
 */
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
        // 2.解析记忆 user 键：认证主体优先，匿名回落 chatId（画像按人存、跨会话召回）
        String memoryUserKey = UserContext.currentUserId(chatId);
        // 3.请求模型
        if (files == null || files.isEmpty()) {
            // 没有附件，纯文本聊天
            return textChat(prompt, memoryUserKey, conversationId, modelProfile, chatId);
        } else {
            // 有附件，多模态聊天
            return multiModalChat(prompt, memoryUserKey, conversationId, files, modelProfile, chatId);
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

    /** 多模态聊天：把附件转成 Media 附到用户消息上，再走统一跟踪流。 */
    private Flux<String> multiModalChat(String prompt,
                                        String memoryUserKey,
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
                prompt, memoryUserKey, modelProfile, chatId, conversationId, "chat",
                spec -> spec.user(t -> t.text(prompt).media(mediaList.toArray(Media[]::new))));
    }

    /** 纯文本聊天：直接把 prompt 作为用户消息走统一跟踪流。 */
    private Flux<String> textChat(String prompt,
                                  String memoryUserKey,
                                  String conversationId,
                                  String modelProfile,
                                  String chatId) {
        return trackedChatStream(
                prompt, memoryUserKey, modelProfile, chatId, conversationId, "chat",
                spec -> spec.user(prompt));
    }

    /**
     * 通用的聊天流封装。上面两个调用方的差异仅在 .user(...) 调用，
     * 因此成本统计、advisor 与 stream() 的配置在此共享。
     * 若没有这个辅助方法，两个方法都会在 .user(prompt) 周围出现
     * 同样的六行代码，而这正是 SpotBugs DB_DUPLICATE_BRANCHES
     * 检查所标记的问题。
     */
    private Flux<String> trackedChatStream(String prompt,
                                          String memoryUserKey,
                                          String modelProfile,
                                          String chatId,
                                          String conversationId,
                                          String endpointTag,
                                          Function<ChatClient.ChatClientRequestSpec, ChatClient.ChatClientRequestSpec> userCustomizer) {
        String tenantId = TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
        ModelRouter.ModelRouteDecision decision = modelRouter.resolve(modelProfile, "chat", tenantId, chatId);
        // 记账按用户原始问题估算；advisor 注入的记忆段未计入（与评测链路
        // 同款低估，演进方向为 advisor 侧回填 usage 统计）。
        long inputTokens = tenantCostService.estimateTokens(prompt);
        tenantCostService.assertBudget(tenantId, decision.costTier(), inputTokens, 600);
        StringBuilder outputCollector = new StringBuilder();
        AtomicBoolean recorded = new AtomicBoolean(false);
        return userCustomizer.apply(chatClient.prompt()
                        .options(ChatOptions.builder().model(decision.model()).build()))
                .advisors(a -> a.param(CONVERSATION_ID, conversationId)
                        // 记忆注入参数：advisor 召回 long/fact 跨会话视图（short 由
                        // ChatMemory advisor 保真注入，防同信息双份），组装期插入
                        // system 消息 —— 不进会话历史，不会逐轮累积。
                        .param(MemoryInjectionAdvisor.MEMORY_TENANT_KEY, tenantId)
                        .param(MemoryInjectionAdvisor.MEMORY_USER_KEY, memoryUserKey))
                .stream()
                .content()
                .doOnNext(outputCollector::append)
                .doFinally(signal -> {
                    if (recorded.compareAndSet(false, true)) {
                        long outputTokens = tenantCostService.estimateTokens(outputCollector.toString());
                        tenantCostService.recordUsage(tenantId, decision.costTier(), inputTokens, outputTokens, endpointTag);
                    }
                    // 本轮结束后用用户原始问题写入 short 记忆并异步提交画像提取
                    // （命中才升级 long），记忆按 userKey 存、chatId 记入 source 溯源。
                    // 两者都是尽力而为：异常在各自内部吞掉，绝不影响流收尾与计费。
                    if (signal == SignalType.ON_COMPLETE) {
                        chatTurnMemoryRecorder.recordTurn(tenantId, memoryUserKey, chatId,
                                prompt, outputCollector.toString());
                        memoryExtractionService.submitAsync(tenantId, memoryUserKey, chatId,
                                prompt, outputCollector.toString());
                    }
                });
    }
}
