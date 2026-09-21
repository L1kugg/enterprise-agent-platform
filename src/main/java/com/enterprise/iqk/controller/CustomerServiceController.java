package com.enterprise.iqk.controller;


import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.memory.MemoryInjectionAdvisor;
import com.enterprise.iqk.repository.ChatHistoryRepository;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.security.UserContext;
import com.enterprise.iqk.service.TenantCostService;
import com.enterprise.iqk.util.ConversationIdHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@RequiredArgsConstructor
@RestController
@RequestMapping("/ai")
public class CustomerServiceController {

    private final ChatClient serviceChatClient;
    private final ModelRouter modelRouter;
    private final TenantCostService tenantCostService;

    private final ChatHistoryRepository chatHistoryRepository;

    @PostMapping(value = "/service", produces = "text/html;charset=utf-8")
    public String service(String prompt,
                          String chatId,
                          @RequestParam(value = "modelProfile", required = false) String modelProfile) {
        // 1.保存会话id
        chatHistoryRepository.save("service", chatId);
        String conversationId = ConversationIdHelper.build("service", chatId);
        String tenantId = TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
        ModelRouter.ModelRouteDecision decision = modelRouter.resolve(modelProfile, "service", tenantId, chatId);
        // 成本治理：与 WorkflowReactAgentService.callModel 采用相同模式
        // （发送前校验、调用后记账），使同步的 /ai/service 端点与流式
        // ReAct 端点计入相同的统计口径。
        long inputTokens = tenantCostService.estimateTokens(prompt);
        tenantCostService.assertBudget(tenantId, decision.costTier(), inputTokens, 600);
        // 2.请求模型（记忆注入：认证主体为 user 键，advisor 组装期插"已知记忆" system 消息）
        String answer = serviceChatClient.prompt()
                .options(ChatOptions.builder().model(decision.model()).build())
                .user(prompt)
                .advisors(a -> a.param(CONVERSATION_ID, conversationId)
                        .param(MemoryInjectionAdvisor.MEMORY_TENANT_KEY, tenantId)
                        .param(MemoryInjectionAdvisor.MEMORY_USER_KEY, UserContext.currentUserId(chatId)))
                .call()
                .content();
        long outputTokens = tenantCostService.estimateTokens(answer);
        tenantCostService.recordUsage(tenantId, decision.costTier(), inputTokens, outputTokens, "service");
        return answer;
    }
}
