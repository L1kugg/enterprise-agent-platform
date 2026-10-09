package com.enterprise.iqk.service;

import com.enterprise.iqk.domain.vo.AgentSessionBranchVO;
import com.enterprise.iqk.domain.vo.AgentSessionMessageVO;
import com.enterprise.iqk.domain.vo.AgentSessionStateVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionHandoffServiceTest {

    private final AgentSessionService agentSessionService = mock(AgentSessionService.class);
    private final SessionHandoffService service = new SessionHandoffService(agentSessionService);

    @Test
    void generatesAndPersistsStructuredHandoffSummary() {
        AgentSessionMessageVO goal = message("user", "为记忆系统设计查询相关度召回");
        AgentSessionMessageVO answer = message("assistant", "已完成：按相关度、置信度和时间衰减排序。下一步请补集成测试。");
        AgentSessionBranchVO branch = new AgentSessionBranchVO();
        branch.setId("root");
        branch.setTitle("记忆优化");
        branch.setMessages(List.of(goal, answer));
        AgentSessionStateVO state = new AgentSessionStateVO();
        state.setId("chat-1");
        state.setTitle("记忆系统优化");
        state.setActiveBranchId("root");
        state.setBranches(List.of(branch));
        when(agentSessionService.get("tenant", "chat-1")).thenReturn(state);
        when(agentSessionService.upsert(org.mockito.ArgumentMatchers.eq("tenant"), org.mockito.ArgumentMatchers.eq("chat-1"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(2));

        AgentSessionStateVO result = service.generate("tenant", "chat-1", null);

        assertThat(result.getHandoffGeneratedAt()).isPositive();
        assertThat(result.getHandoffSummary())
                .contains("会话交接摘要", "为记忆系统设计查询相关度召回", "按相关度、置信度和时间衰减排序");
        verify(agentSessionService).upsert(org.mockito.ArgumentMatchers.eq("tenant"),
                org.mockito.ArgumentMatchers.eq("chat-1"), org.mockito.ArgumentMatchers.same(result));
    }

    private AgentSessionMessageVO message(String role, String content) {
        AgentSessionMessageVO message = new AgentSessionMessageVO();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
