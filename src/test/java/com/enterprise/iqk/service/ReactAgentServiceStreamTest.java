package com.enterprise.iqk.service;

import com.enterprise.iqk.agent.harness.ActionSchemaRegistry;
import com.enterprise.iqk.agent.harness.AgentAction;
import com.enterprise.iqk.agent.harness.AgentHarnessService;
import com.enterprise.iqk.agent.harness.AgentObservation;
import com.enterprise.iqk.agent.harness.McpToolCatalog;
import com.enterprise.iqk.agent.harness.PlannerActionCatalog;
import com.enterprise.iqk.config.properties.AgentRuntimeProperties;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.llm.PlatformChatModelRegistry;
import com.enterprise.iqk.memory.ChatTurnMemoryRecorder;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.service.agent.AgentChatRuntime;
import com.enterprise.iqk.service.agent.AgentInvocationLogRecorder;
import com.enterprise.iqk.service.agent.AgentPromptMerger;
import com.enterprise.iqk.service.agent.AgentRuntimeConfigService;
import com.enterprise.iqk.service.agent.AgentSafetyService;
import com.enterprise.iqk.service.agent.ReActPlannerPrompts;
import com.enterprise.iqk.service.agent.ReActSupport;
import com.enterprise.iqk.testutil.TestGuards;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话链路流式 ReAct（SSE）行为契约（与留痕链路 WorkflowReactAgentServiceStreamTest 对齐）：
 * 1) 真流式 —— 每步完成即发 trace 帧（帧序不变：trace*→token*→done），首帧不再等整个循环跑完；
 * 2) finish 直答 —— 直答切片匀速播放为 token 帧；
 * 3) 失败 —— 发 error 帧。
 */
class ReactAgentServiceStreamTest {

    private static final String FINISH_JSON =
            "{\"thought\":\"可直答\",\"action\":\"finish\",\"answer\":\"推荐《Java并发实战》\"}";
    private static final String RAG_STEP_JSON =
            "{\"thought\":\"先查课程\",\"action\":\"rag_search\",\"action_input\":{\"query\":\"java 课程\"}}";

    private AgentHarnessService harness;
    private ChatClient.CallResponseSpec callSpec;
    private ReactAgentService service;

    @BeforeEach
    void setUp() {
        harness = mock(AgentHarnessService.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(FINISH_JSON);
        when(harness.execute(any(AgentAction.class)))
                .thenReturn(AgentObservation.success("builtin", Map.of("rows", 2), 3));
        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.resolve(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new ModelRouter.ModelRouteDecision("balanced", "model-a", "standard", false,
                        "profile_match", "", "", null));
        PlannerActionCatalog catalog =
                new PlannerActionCatalog(new ActionSchemaRegistry(), mock(McpToolCatalog.class));
        ReactResponseFormatter formatter = new ReactResponseFormatter(new ObjectMapper());
        com.enterprise.iqk.service.agent.AgentToolCatalog toolCatalog =
                new com.enterprise.iqk.service.agent.AgentToolCatalog(
                        mock(com.enterprise.iqk.mapper.AgentBindingMapper.class),
                        mock(com.enterprise.iqk.mapper.PlatformAssetMapper.class),
                        new ObjectMapper());
        // 智能体运行时桩：agentId 不传时全部穿透，模型调用只走档位路由路径（scenario=react）
        AgentChatRuntime agentRuntime = new AgentChatRuntime(
                mock(AgentRuntimeConfigService.class),
                mock(AgentSafetyService.class),
                mock(PlatformChatModelRegistry.class),
                mock(AgentInvocationLogRecorder.class),
                new AgentRuntimeProperties(),
                TestGuards.real(),
                mock(TenantCostService.class),
                chatClient);
        service = new ReactAgentService(
                modelRouter,
                new ReactDecisionParser(catalog, new ObjectMapper()),
                formatter,
                mock(ChatTurnMemoryRecorder.class),
                new AgentPromptMerger(),
                agentRuntime,
                new ReActPlannerPrompts(catalog, toolCatalog, formatter),
                new ReActSupport(harness, mock(MemoryService.class), new SimpleMeterRegistry()));
    }

    private ReactChatRequestVO request() {
        ReactChatRequestVO request = new ReactChatRequestVO();
        request.setPrompt("帮我推荐一门课");
        request.setChatId("chat-1");
        request.setModelProfile("balanced");
        return request;
    }

    @Test
    void emitsTraceTokenDoneInOrderWithDirectAnswerChunked() {
        List<String> frames = service.stream(request()).collectList().block();

        // 直答也是切片匀速播放：trace 首帧、done 末帧、中间全是 token 片
        assertThat(frames.get(0)).startsWith("event: trace").contains("finish");
        assertThat(frames.get(frames.size() - 1)).startsWith("event: done");
        assertThat(frames.size()).isGreaterThanOrEqualTo(3);
        assertThat(frames.subList(1, frames.size() - 1))
                .allSatisfy(frame -> assertThat(frame).startsWith("event: token"));
        assertThat(joinedTokenText(frames)).isEqualTo("推荐《Java并发实战》");
    }

    @Test
    void multiStepRunEmitsEachTraceFrameAsItsStepCompletes() {
        when(callSpec.content()).thenReturn(RAG_STEP_JSON, FINISH_JSON);

        List<String> frames = service.stream(request()).collectList().block();

        // 两步各发一条 trace 帧，再 token 片（匀速播放）、done —— 帧序不变，只是 trace 不再等整循环跑完
        assertThat(frames.get(0)).startsWith("event: trace").contains("rag_search");
        assertThat(frames.get(1)).startsWith("event: trace").contains("finish");
        assertThat(frames.get(frames.size() - 1)).startsWith("event: done");
        assertThat(frames.subList(2, frames.size() - 1))
                .allSatisfy(frame -> assertThat(frame).startsWith("event: token"));
        assertThat(joinedTokenText(frames)).isEqualTo("推荐《Java并发实战》");
        verify(harness).execute(any(AgentAction.class));
    }

    @Test
    void routeFailureEmitsErrorFrame() {
        ReactChatRequestVO broken = request();
        broken.setModelProfile(null);
        ReactAgentService spiedRouter = service;
        // 路由解析在 defer 内抛错 → onErrorResume 转 error 帧（与同步链路 recordError 同源）
        ReactChatRequestVO request = new ReactChatRequestVO() {
            @Override
            public String getModelProfile() {
                throw new RuntimeException("router down");
            }
        };
        request.setPrompt(broken.getPrompt());
        request.setChatId(broken.getChatId());

        List<String> frames = spiedRouter.stream(request).collectList().block();

        assertThat(frames).hasSize(1);
        assertThat(frames.get(0)).startsWith("event: error").contains("router down");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 把所有 token 帧携带的增量文本按序拼回，断言切片不丢字、不乱序。 */
    private static String joinedTokenText(List<String> frames) {
        return frames.stream()
                .filter(frame -> frame.startsWith("event: token"))
                .map(frame -> frame.substring(frame.indexOf("data: ") + "data: ".length()).trim())
                .map(json -> {
                    try {
                        JsonNode node = JSON.readTree(json);
                        return node.get("token").asText();
                    } catch (Exception ex) {
                        throw new IllegalStateException("token 帧数据不是合法 JSON: " + json, ex);
                    }
                })
                .collect(Collectors.joining());
    }
}
