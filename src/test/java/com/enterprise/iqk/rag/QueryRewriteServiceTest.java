package com.enterprise.iqk.rag;

import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.llm.ModelCallGuard;
import com.enterprise.iqk.llm.ModelRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import org.mockito.Mockito;

/**
 * 多轮查询改写（FR-检索质量）：禁用/首轮零成本短路；有历史时 LLM 改写；
 * 改写失败回退原查询。
 */
class QueryRewriteServiceTest {

    private static final ModelRouter.ModelRouteDecision DECISION = new ModelRouter.ModelRouteDecision(
            "economy", "test-model", "economy", false, null, null, null, null);

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private com.enterprise.iqk.config.MysqlChatMemory chatMemory;
    private ModelRouter modelRouter;
    private ModelCallGuard modelCallGuard;
    private RagProperties ragProperties;
    private QueryRewriteService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatClient = mock(ChatClient.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
        chatMemory = mock(com.enterprise.iqk.config.MysqlChatMemory.class);
        modelRouter = mock(ModelRouter.class);
        modelCallGuard = mock(ModelCallGuard.class);
        ragProperties = new RagProperties();
        when(modelRouter.resolve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(DECISION);
        when(modelCallGuard.call(anyString(), any())).thenAnswer(inv -> {
            var supplier = (java.util.function.Supplier<?>) inv.getArgument(1);
            return supplier == null ? "" : supplier.get();
        });
        when(chatClient.prompt()).thenReturn(requestSpec);

        service = new QueryRewriteService(chatClient, chatMemory, modelRouter,
                modelCallGuard, ragProperties);
    }

    private void historyOf(String... turns) {
        var messages = new java.util.ArrayList<org.springframework.ai.chat.messages.Message>();
        for (int i = 0; i < turns.length; i++) {
            messages.add(i % 2 == 0
                    ? new UserMessage(turns[i])
                    : new AssistantMessage(turns[i]));
        }
        when(chatMemory.get(eq("conv-1"), eq(8))).thenReturn(messages);
    }

    private void llmReturns(String rewritten) {
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(rewritten);
    }

    @Test
    void disabledFlagReturnsOriginalQueryWithoutAnyCall() {
        ragProperties.setQueryRewriteEnabled(false);

        String result = service.rewriteForRetrieval("那它呢", "conv-1", "t-1");

        assertThat(result).isEqualTo("那它呢");
        verifyNoInteractions(chatMemory, chatClient);
    }

    @Test
    void firstTurnSkipsRewriteZeroCost() {
        historyOf(); // 无历史

        String result = service.rewriteForRetrieval("什么是向量检索", "conv-1", "t-1");

        assertThat(result).isEqualTo("什么是向量检索");
        verifyNoInteractions(modelRouter, modelCallGuard);
    }

    @Test
    void followUpQuestionIsRewrittenIntoStandaloneQuery() {
        historyOf("青鸾烟火是什么", "是调度系统的核心组件");
        llmReturns("调度系统核心组件青鸾烟火的工作原理是什么");

        String result = service.rewriteForRetrieval("那它的工作原理呢", "conv-1", "t-1");

        assertThat(result).isEqualTo("调度系统核心组件青鸾烟火的工作原理是什么");
    }

    @Test
    void llmFailureFallsBackToOriginalQuery() {
        historyOf("之前的问题", "之前的回答");
        when(modelCallGuard.call(anyString(), any())).thenThrow(new RuntimeException("cb open"));

        String result = service.rewriteForRetrieval("那它呢", "conv-1", "t-1");

        assertThat(result).isEqualTo("那它呢");
    }

    @Test
    void blankLlmOutputFallsBackToOriginalQuery() {
        historyOf("之前的问题", "之前的回答");
        llmReturns("  ");

        String result = service.rewriteForRetrieval("那它呢", "conv-1", "t-1");

        assertThat(result).isEqualTo("那它呢");
    }

    @Test
    void rewriteOutputPrefixIsStripped() {
        historyOf("之前的问题", "之前的回答");
        llmReturns("查询:改写后的查询内容");

        String result = service.rewriteForRetrieval("继续", "conv-1", "t-1");

        assertThat(result).isEqualTo("改写后的查询内容");
    }
}
