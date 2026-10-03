package com.enterprise.iqk.agent.workflow;

import com.enterprise.iqk.agent.harness.AgentAction;
import com.enterprise.iqk.agent.harness.AgentHarnessService;
import com.enterprise.iqk.agent.harness.AgentObservation;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.service.TenantCostService;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式 ReAct（SSE）行为契约：
 * 1) 真流式 —— 每步完成即发 trace 帧（帧序不变：trace*→token*→done），任务收 DONE；
 * 2) 断连（Reactor cancel 不是 error）—— 任务守卫式置 FAILED（abandonTask），
 *    绝不永久停在非终态，也不覆盖恰已完成的任务；
 * 3) 失败 —— 发 error 帧并置任务 FAILED。
 */
class WorkflowReactAgentServiceStreamTest {

    private static final String FINISH_JSON =
            "{\"thought\":\"可直答\",\"action\":\"finish\",\"answer\":\"推荐《Java并发实战》\"}";
    private static final String RAG_STEP_JSON =
            "{\"thought\":\"先查课程\",\"action\":\"rag_search\",\"action_input\":{\"query\":\"java 课程\"}}";

    private AgentWorkflowEngine workflowEngine;
    private AgentHarnessService harness;
    private ChatClient chatClient;
    private ChatClient.CallResponseSpec callSpec;
    private WorkflowReactAgentService service;

    @BeforeEach
    void setUp() {
        workflowEngine = mock(AgentWorkflowEngine.class);
        harness = mock(AgentHarnessService.class);
        chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(FINISH_JSON);
        when(workflowEngine.startTask(anyString(), anyString(), anyString(),
                ArgumentMatchers.<String>nullable(String.class), ArgumentMatchers.<String>nullable(String.class),
                ArgumentMatchers.<String>nullable(String.class)))
                .thenReturn(AgentTaskRecord.builder().taskId("task-1").tenantId("tenant-1").build());
        when(workflowEngine.startStep(anyString(), anyString(), ArgumentMatchers.anyInt(), any()))
                .thenReturn(AgentStepRecord.builder().stepId("step-1").taskId("task-1").build());
        when(harness.execute(any(AgentAction.class)))
                .thenReturn(AgentObservation.success("builtin", Map.of("rows", 2), 3));
        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.resolve(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new ModelRouter.ModelRouteDecision("balanced", "model-a", "standard", false,
                        "profile_match", "", "", null));
        service = new WorkflowReactAgentService(workflowEngine, harness, chatClient,
                modelRouter, mock(TenantCostService.class), new ObjectMapper(), new SimpleMeterRegistry());
    }

    private ReactChatRequestVO request() {
        ReactChatRequestVO request = new ReactChatRequestVO();
        request.setPrompt("帮我推荐一门课");
        request.setChatId("chat-1");
        request.setModelProfile("balanced");
        return request;
    }

    @Test
    void emitsTraceTokenDoneInOrderAndCompletesTask() {
        List<String> frames = service.stream(request()).collectList().block();

        // 直答也是切片匀速播放：trace 首帧、done 末帧、中间全是 token 片
        assertThat(frames.get(0)).startsWith("event: trace").contains("finish");
        assertThat(frames.get(frames.size() - 1)).startsWith("event: done");
        assertThat(frames.size()).isGreaterThanOrEqualTo(3);
        assertThat(frames.subList(1, frames.size() - 1))
                .allSatisfy(frame -> assertThat(frame).startsWith("event: token"));
        assertThat(joinedTokenText(frames)).isEqualTo("推荐《Java并发实战》");
        verify(workflowEngine).completeTask("task-1", WorkflowState.DONE, "推荐《Java并发实战》");
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
        verify(workflowEngine).completeTask(eq("task-1"), eq(WorkflowState.DONE), anyString());
    }

    @Test
    void clientDisconnectCancelsTaskInsteadOfOrphaningIt() {
        // take(1)：收到首帧即取消 —— 模拟用户关浏览器（Reactor cancel 信号，非 error）
        List<String> frames = service.stream(request()).take(1).collectList().block();

        assertThat(frames).hasSize(1);
        verify(workflowEngine).abandonTask(eq("task-1"), contains("client disconnected"));
        // 取消路径不走完成也不走失败：任务由守卫式收尾兜住，不产生孤儿
        verify(workflowEngine, never()).completeTask(anyString(), any(), anyString());
        verify(workflowEngine, never()).failTask(anyString(), anyString());
    }

    @Test
    void failureEmitsErrorFrameAndNeverLeavesTaskUnfinished() {
        when(workflowEngine.startTask(anyString(), anyString(), anyString(),
                ArgumentMatchers.<String>nullable(String.class), ArgumentMatchers.<String>nullable(String.class),
                ArgumentMatchers.<String>nullable(String.class)))
                .thenThrow(new RuntimeException("db down"));

        List<String> frames = service.stream(request()).collectList().block();

        assertThat(frames).hasSize(1);
        assertThat(frames.get(0)).startsWith("event: error").contains("db down");
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
