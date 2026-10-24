package com.enterprise.iqk.service.agent;

import com.enterprise.iqk.config.properties.AgentRuntimeProperties;
import com.enterprise.iqk.domain.agent.AgentRuntimeConfig;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.llm.ModelCallGuard;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.llm.PlatformChatModelRegistry;
import com.enterprise.iqk.service.TenantCostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentChatRuntimeTest {

    private AgentRuntimeConfigService configService;
    private AgentSafetyService safetyService;
    private PlatformChatModelRegistry chatModelRegistry;
    private AgentInvocationLogRecorder invocationLogRecorder;
    private AgentChatRuntime runtime;

    private static final PlatformChatModelRegistry.BoundChatModel BOUND =
            new PlatformChatModelRegistry.BoundChatModel(mock(ChatClient.class), "qwen-max", 9L);

    @BeforeEach
    void setUp() {
        configService = mock(AgentRuntimeConfigService.class);
        safetyService = mock(AgentSafetyService.class);
        chatModelRegistry = mock(PlatformChatModelRegistry.class);
        invocationLogRecorder = mock(AgentInvocationLogRecorder.class);
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setModelFallbackEnabled(true);
        runtime = new AgentChatRuntime(configService, safetyService, chatModelRegistry,
                invocationLogRecorder, properties, mock(ModelCallGuard.class),
                mock(TenantCostService.class), mock(ChatClient.class));
        when(safetyService.interceptInput(anyString(), any(), anyString()))
                .thenReturn(SafetyMatchResult.notBlocked());
    }

    @Test
    void prepareWithoutAgentAndModelReturnsNull() {
        assertThat(runtime.prepare(request(null, null), "tenant-1")).isNull();
    }

    @Test
    void prepareWithUserSelectedModelBuildsAutomaticRunState() {
        when(chatModelRegistry.resolve(9L, "tenant-1")).thenReturn(BOUND);

        AgentChatRuntime.RunState state = runtime.prepare(request(null, 9L), "tenant-1");

        assertThat(state).isNotNull();
        assertThat(state.blocked()).isFalse();
        // 占位配置：无角色/绑定，短记忆条数与无智能体路径默认一致
        assertThat(state.config().agentId()).isNull();
        assertThat(state.config().prompt()).isNull();
        assertThat(state.config().memoryTurns()).isEqualTo(4);
        verify(chatModelRegistry).resolve(9L, "tenant-1");
    }

    @Test
    void prepareAgentWithoutBindingFallsToUserSelectedModel() {
        AgentRuntimeConfig config = config(null);
        when(configService.loadForChat("tenant-1", 7L, false)).thenReturn(config);
        when(chatModelRegistry.resolve(9L, "tenant-1")).thenReturn(BOUND);

        AgentChatRuntime.RunState state = runtime.prepare(request(7L, 9L), "tenant-1");

        assertThat(state).isNotNull();
        verify(chatModelRegistry).resolve(9L, "tenant-1");
    }

    @Test
    void prepareAgentBindingWinsOverUserSelectedModel() {
        AgentRuntimeConfig config = config(5L);
        when(configService.loadForChat("tenant-1", 7L, false)).thenReturn(config);
        when(chatModelRegistry.resolve(5L, "tenant-1")).thenReturn(BOUND);

        AgentChatRuntime.RunState state = runtime.prepare(request(7L, 9L), "tenant-1");

        assertThat(state).isNotNull();
        // 绑定优先：手选模型不被解析
        verify(chatModelRegistry).resolve(5L, "tenant-1");
        verify(chatModelRegistry, never()).resolve(9L, "tenant-1");
    }

    @Test
    void enrichBackfillsModelFieldsWithoutInvocationLogForAutomaticState() {
        AgentChatRuntime.RunState state = new AgentChatRuntime.RunState(AgentRuntimeConfig.automatic());

        var response = runtime.enrichAndRecord(request(null, 9L), "tenant-1", state,
                com.enterprise.iqk.domain.vo.ReactChatResponseVO.builder().build(), 100L);

        // 占位配置（手选模型/非智能体）：回填模型来源字段，但不回填 agentId、不写智能体调用日志
        assertThat(response.getModelSource()).isEqualTo("router");
        assertThat(response.getAgentId()).isNull();
        verify(invocationLogRecorder, never()).recordSuccess(any(), any(), anyBoolean(), anyLong(),
                anyLong(), anyLong());
    }

    @Test
    void enrichRecordsInvocationLogForAgentScopedState() {
        AgentChatRuntime.RunState state = new AgentChatRuntime.RunState(config(5L));

        var response = runtime.enrichAndRecord(request(5L, null), "tenant-1", state,
                com.enterprise.iqk.domain.vo.ReactChatResponseVO.builder().build(), 100L);

        assertThat(response.getAgentId()).isEqualTo(5L);
        verify(invocationLogRecorder).recordSuccess(any(), any(), anyBoolean(), anyLong(),
                anyLong(), anyLong());
    }

    private ReactChatRequestVO request(Long agentId, Long modelServiceId) {
        ReactChatRequestVO request = new ReactChatRequestVO();
        request.setPrompt("你好");
        request.setChatId("chat-1");
        request.setAgentId(agentId);
        request.setModelServiceId(modelServiceId);
        return request;
    }

    private AgentRuntimeConfig config(Long modelServiceId) {
        return new AgentRuntimeConfig(5L, "助手", "PUBLISHED", "你是助手", null, 10, null,
                null, List.of(), false, modelServiceId, modelServiceId == null ? null : "qwen-plus",
                List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
