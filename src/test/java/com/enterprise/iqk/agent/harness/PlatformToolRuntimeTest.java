package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.AgentBindingMapper;
import com.enterprise.iqk.mapper.AuditLogMapper;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformToolRuntimeTest {

    @Mock
    private PlatformAssetMapper assetMapper;

    @Mock
    private AgentBindingMapper bindingMapper;

    @Mock
    private AuditLogMapper auditLogMapper;

    /** 真实服务实例的 spy：parseToolConfig 走真实逻辑，仅 stub invoke（不发真实 HTTP/SQL）。 */
    private final com.enterprise.iqk.service.platform.ToolInvocationService invocation =
            org.mockito.Mockito.spy(new com.enterprise.iqk.service.platform.ToolInvocationService(
                    mock(com.enterprise.iqk.service.platform.PlatformSqlTestService.class),
                    new ObjectMapper()));

    private PlatformToolRuntime runtime() {
        return new PlatformToolRuntime(assetMapper, bindingMapper, invocation, auditLogMapper,
                new SimpleMeterRegistry());
    }

    private AgentAction toolCallAction(Long agentId, String tool, Map<String, Object> args) {
        return new AgentAction("tool_call", Map.of("tool", tool, "arguments", args),
                "用户问题", "t-1", "chat-1", null, "", "", agentId, false);
    }

    private void bindTools(Long agentId, Long... toolIds) {
        when(bindingMapper.findByAgent(eq("agent_tool_binding"), eq("t-1"), eq(agentId)))
                .thenReturn(java.util.Arrays.stream(toolIds)
                        .map(id -> com.enterprise.iqk.domain.agent.AgentBindingRow.builder()
                                .resourceId(id).resourceType("tools").bindingOrder(1).build())
                        .toList());
    }

    private PlatformAsset toolAsset(Long id, String name, String configJson) {
        return PlatformAsset.builder()
                .id(id).tenantId("t-1").assetType("tools").name(name)
                .status("ENABLED").configJson(configJson)
                .build();
    }

    @Test
    void executesBoundFormApiTool() throws Exception {
        bindTools(7L, 4L);
        when(assetMapper.findByIdAndTypeVisible(4L, "t-1", "tools"))
                .thenReturn(toolAsset(4L, "汇率查询", "{\"toolType\":\"FORM_API\",\"executionConfirm\":false}"));
        doReturn(Map.of("status", 200, "responseSummary", "{\"result\":\"success\"}"))
                .when(invocation).invoke(any(), any());

        AgentObservation observation = runtime().execute(toolCallAction(7L, "汇率查询", Map.of("base", "USD")));

        assertThat(observation.toMap()).containsEntry("status", "success");
        assertThat(observation.toMap().get("tool")).isEqualTo("汇率查询");
    }

    @Test
    void rejectsToolNotInAgentBindings() {
        when(bindingMapper.findByAgent(eq("agent_tool_binding"), eq("t-1"), eq(7L)))
                .thenReturn(List.of());

        AgentObservation observation = runtime().execute(toolCallAction(7L, "汇率查询", Map.of()));

        Map<String, Object> map = observation.toMap();
        assertThat(map).containsEntry("status", "error");
        assertThat(String.valueOf(map.get("message"))).contains("未绑定");
    }

    @Test
    void rejectsToolCallWithoutAgentContext() {
        // 默认助手（agentId=0）：无绑定可用
        AgentObservation observation = runtime().execute(toolCallAction(0L, "任意工具", Map.of()));

        assertThat(observation.toMap()).containsEntry("status", "error");
    }

    @Test
    void rejectsDisabledToolEvenIfBound() {
        bindTools(7L, 4L);
        when(assetMapper.findByIdAndTypeVisible(4L, "t-1", "tools"))
                .thenReturn(PlatformAsset.builder()
                        .id(4L).assetType("tools").name("汇率查询").status("DISABLED").build());

        AgentObservation observation = runtime().execute(toolCallAction(7L, "汇率查询", Map.of()));

        assertThat(observation.toMap()).containsEntry("status", "error");
    }

    @Test
    void refusesExecutionConfirmToolsConservatively() {
        bindTools(7L, 9L);
        when(assetMapper.findByIdAndTypeVisible(9L, "t-1", "tools"))
                .thenReturn(toolAsset(9L, "高危工具", "{\"toolType\":\"FORM_API\",\"executionConfirm\":true}"));

        AgentObservation observation = runtime().execute(toolCallAction(7L, "高危工具", Map.of()));

        Map<String, Object> map = observation.toMap();
        assertThat(map).containsEntry("status", "error");
        assertThat(String.valueOf(map.get("message"))).contains("人工确认");
    }

    @Test
    void delegatesSqlToolToInvocationService() throws Exception {
        bindTools(7L, 5L);
        when(assetMapper.findByIdAndTypeVisible(5L, "t-1", "tools"))
                .thenReturn(toolAsset(5L, "工单查询", "{\"toolType\":\"SQL\",\"databaseId\":3,\"sql\":\"SELECT 1\"}"));
        doReturn(Map.of("status", 200, "responseSummary", "{\"rowCount\":1}"))
                .when(invocation).invoke(any(), any());

        AgentObservation observation = runtime().execute(toolCallAction(7L, "工单查询", Map.of()));

        assertThat(observation.toMap()).containsEntry("status", "success");
    }

    @Test
    void convertsInvocationFailureToErrorObservation() throws Exception {
        bindTools(7L, 4L);
        when(assetMapper.findByIdAndTypeVisible(4L, "t-1", "tools"))
                .thenReturn(toolAsset(4L, "汇率查询", "{\"toolType\":\"FORM_API\"}"));
        doThrow(new RuntimeException("connection timeout")).when(invocation).invoke(any(), any());

        AgentObservation observation = runtime().execute(toolCallAction(7L, "汇率查询", Map.of()));

        Map<String, Object> map = observation.toMap();
        assertThat(map).containsEntry("status", "error");
        assertThat(String.valueOf(map.get("message"))).contains("connection timeout");
    }

    @Test
    void actionNameMatchingIsCaseInsensitive() throws Exception {
        bindTools(7L, 4L);
        when(assetMapper.findByIdAndTypeVisible(4L, "t-1", "tools"))
                .thenReturn(toolAsset(4L, "Exchange Rate", "{\"toolType\":\"FORM_API\"}"));
        doReturn(Map.of("status", 200)).when(invocation).invoke(any(), any());

        AgentObservation observation = runtime().execute(toolCallAction(7L, "exchange rate", Map.of()));

        assertThat(observation.toMap()).containsEntry("status", "success");
    }

    @Test
    void unknownActionFieldsAreGuardedBySchema() {
        // 守卫层测试点：tool_call 的 schema 外字段（如 secret 注入）必须被拒——此处验证注册表形状
        var registry = new ActionSchemaRegistry();
        var schema = registry.find("tool_call").orElseThrow();
        assertThat(schema.requiredFields()).containsExactlyInAnyOrder("tool", "arguments");
        assertThat(schema.sensitiveFields()).containsExactly("arguments");
        assertThat(schema.trustedOnly()).isFalse();
        assertThat(schema.riskLevel()).isEqualTo("external");
    }
}
