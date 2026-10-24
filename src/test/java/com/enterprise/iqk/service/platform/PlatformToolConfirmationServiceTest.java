package com.enterprise.iqk.service.platform;

import com.enterprise.iqk.domain.AgentToolConfirmationRecord;
import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.AgentBindingMapper;
import com.enterprise.iqk.mapper.AgentToolConfirmationMapper;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 工具人工确认生命周期（R7.3）：创建落 PENDING 快照；审批 CAS 抢占；
 * 审批时刻重验绑定（解绑/停用不许执行）；取消与非 PENDING 冲突语义。
 */
@ExtendWith(MockitoExtension.class)
class PlatformToolConfirmationServiceTest {

    @Mock
    private AgentToolConfirmationMapper confirmationMapper;

    private PlatformToolConfirmationService service(PlatformToolInvoker invoker) {
        ToolInvocationService invocation = new ToolInvocationService(null,
                new CodeToolSandboxService(new ObjectMapper()), new ObjectMapper(),
                new com.enterprise.iqk.security.SecretCipher("", new ObjectMapper()));
        return new PlatformToolConfirmationService(confirmationMapper, invoker, invocation, new ObjectMapper(),
                new org.springframework.context.ApplicationEventPublisher() {
                    @Override
                    public void publishEvent(Object event) {
                    }
                });
    }

    @Test
    void createStoresPendingSnapshotWithExpiry() {
        when(confirmationMapper.insert(any(AgentToolConfirmationRecord.class))).thenReturn(1);
        PlatformToolConfirmationService service = service(null);
        PlatformAsset tool = tool(9L, "高危工具");

        service.create(7L, "t-1", "chat-1", tool, Map.of("amount", 100));

        ArgumentCaptor<AgentToolConfirmationRecord> captor =
                ArgumentCaptor.forClass(AgentToolConfirmationRecord.class);
        org.mockito.Mockito.verify(confirmationMapper).insert(captor.capture());
        AgentToolConfirmationRecord record = captor.getValue();
        assertThat(record.getStatus()).isEqualTo("PENDING");
        assertThat(record.getArgumentsJson()).contains("amount");
        assertThat(record.getToolAssetId()).isEqualTo(9L);
        assertThat(record.getExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(9));
    }

    @Test
    void approveExecutesToolAndFinishesAsExecuted() throws Exception {
        when(confirmationMapper.findByIdAndTenant(55L, "t-1")).thenReturn(pending());
        when(confirmationMapper.casApprove(eq(55L), eq("t-1"), any(LocalDateTime.class))).thenReturn(1);

        PlatformToolInvoker invoker = invokerReturningTool("高危工具",
                "{\"toolType\":\"FORM_API\",\"method\":\"GET\",\"url\":\"http://x/y\"}");
        PlatformToolConfirmationService service = service(invoker);

        Map<String, Object> result = service.approve(55L, "t-1");

        assertThat(result).containsEntry("status", "EXECUTED");
        @SuppressWarnings("unchecked")
        Map<String, Object> invokeResult = (Map<String, Object>) result.get("result");
        assertThat(invokeResult).containsEntry("status", 200);
        org.mockito.Mockito.verify(confirmationMapper).casFinish(
                eq(55L), eq("EXECUTED"), anyString(), org.mockito.ArgumentMatchers.isNull(),
                any(LocalDateTime.class));
    }

    @Test
    void approveFailsWhenToolNoLongerBound() {
        when(confirmationMapper.findByIdAndTenant(55L, "t-1")).thenReturn(pending());
        when(confirmationMapper.casApprove(eq(55L), eq("t-1"), any(LocalDateTime.class))).thenReturn(1);
        // 审批时刻绑定已被移除：findBoundTool 返回 null
        AgentBindingMapper bindingMapper = org.mockito.Mockito.mock(AgentBindingMapper.class);
        when(bindingMapper.findByAgent(anyString(), anyString(), anyLong()))
                .thenReturn(java.util.List.of());
        PlatformAssetMapper assetMapper = org.mockito.Mockito.mock(PlatformAssetMapper.class);
        ToolInvocationService invocation = new ToolInvocationService(null,
                new CodeToolSandboxService(new ObjectMapper()), new ObjectMapper(),
                new com.enterprise.iqk.security.SecretCipher("", new ObjectMapper()));
        PlatformToolInvoker invoker = new PlatformToolInvoker(assetMapper, bindingMapper, invocation);
        PlatformToolConfirmationService service = service(invoker);

        assertThatThrownBy(() -> service.approve(55L, "t-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getReason()).contains("已不在该智能体的绑定列表"));
        org.mockito.Mockito.verify(confirmationMapper).casFinish(
                eq(55L), eq("FAILED"), org.mockito.ArgumentMatchers.isNull(),
                anyString(), any(LocalDateTime.class));
    }

    @Test
    void approveConflictsWhenCasLost() {
        when(confirmationMapper.findByIdAndTenant(55L, "t-1")).thenReturn(pending());
        when(confirmationMapper.casApprove(eq(55L), eq("t-1"), any(LocalDateTime.class))).thenReturn(0);
        PlatformToolConfirmationService service = service(null);

        assertThatThrownBy(() -> service.approve(55L, "t-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void cancelConflictsWhenNotPending() {
        when(confirmationMapper.findByIdAndTenant(55L, "t-1")).thenReturn(pending());
        when(confirmationMapper.casCancel(eq(55L), eq("t-1"), any(LocalDateTime.class))).thenReturn(0);
        PlatformToolConfirmationService service = service(null);

        assertThatThrownBy(() -> service.cancel(55L, "t-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode().value()).isEqualTo(409));
    }

    private AgentToolConfirmationRecord pending() {
        return AgentToolConfirmationRecord.builder()
                .id(55L).tenantId("t-1").agentId(7L).chatId("chat-1")
                .toolAssetId(9L).toolName("高危工具")
                .argumentsJson("{\"amount\":100}")
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
    }

    private PlatformAsset tool(Long id, String name) {
        return PlatformAsset.builder()
                .id(id).tenantId("t-1").assetType("tools").name(name)
                .status("ENABLED").configJson("{}")
                .build();
    }

    /** 绑定查找返回目标工具的真实 invoker；invoke 走 mock 截获（不发真实 HTTP）。 */
    private PlatformToolInvoker invokerReturningTool(String name, String configJson) throws Exception {
        PlatformAssetMapper assetMapper = org.mockito.Mockito.mock(PlatformAssetMapper.class);
        when(assetMapper.findByIdAndTypeVisible(9L, "t-1", "tools"))
                .thenReturn(tool(9L, name));
        // id 一致性校验用，绑定行也要带上
        AgentBindingMapper bindingMapper = org.mockito.Mockito.mock(AgentBindingMapper.class);
        when(bindingMapper.findByAgent(anyString(), anyString(), anyLong()))
                .thenReturn(java.util.List.of(com.enterprise.iqk.domain.agent.AgentBindingRow.builder()
                        .resourceId(9L).resourceType("tools").bindingOrder(1).build()));
        ToolInvocationService invocation = org.mockito.Mockito.mock(ToolInvocationService.class);
        when(invocation.invoke(any(), any())).thenReturn(Map.of("status", 200));
        return new PlatformToolInvoker(assetMapper, bindingMapper, invocation);
    }
}
