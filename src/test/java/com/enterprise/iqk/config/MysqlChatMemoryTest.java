package com.enterprise.iqk.config;

import com.enterprise.iqk.config.properties.ChatMemoryProperties;
import com.enterprise.iqk.domain.Conversation;
import com.enterprise.iqk.mapper.ConversationMapper;
import com.enterprise.iqk.service.TenantCostService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MysqlChatMemoryTest {

    private final ConversationMapper mapper = mock(ConversationMapper.class);
    private final TenantCostService costService = mock(TenantCostService.class);
    private final ChatMemoryProperties properties = new ChatMemoryProperties();
    private final MysqlChatMemory memory = new MysqlChatMemory(mapper, properties, costService);

    @Test
    void skipsUnsupportedMessageTypesWhenReadingHistory() {
        when(mapper.findLatestMessages("public", "chat-1", 3)).thenReturn(List.of(
                conversation(MessageType.ASSISTANT.getValue(), "answer"),
                conversation("tool", "internal"),
                conversation(MessageType.USER.getValue(), "question")
        ));

        List<Message> messages = memory.get("chat-1", 3);

        assertThat(messages).hasSize(2);
        assertThat(messages).extracting(Message::getText).containsExactly("question", "answer");
    }

    @Test
    void defaultWindowReadsConfiguredMaxMessages() {
        properties.setMaxMessages(7);
        when(mapper.findLatestMessages("public", "chat-1", 7)).thenReturn(List.of());
        memory.get("chat-1");
        verify(mapper).findLatestMessages("public", "chat-1", 7);
    }

    @Test
    void addSkipsSystemMessagesFromPersistence() {
        memory.add("chat-1", List.of(
                new org.springframework.ai.chat.messages.SystemMessage("检索参考资料……"),
                new org.springframework.ai.chat.messages.UserMessage("问题"),
                new org.springframework.ai.chat.messages.AssistantMessage("回答")));

        // SYSTEM 不入历史（每请求的检索上下文/记忆注入永不做历史，§18.2）：只落 user/assistant 两条
        org.mockito.ArgumentCaptor<Conversation> captor =
                org.mockito.ArgumentCaptor.forClass(Conversation.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Conversation::getType)
                .containsExactly(MessageType.USER.getValue(), MessageType.ASSISTANT.getValue());
    }

    @Test
    void trimsOldestTurnsWhenExceedingTokenBudget() {
        // DB 倒序返回（最新在前）：a2/q2 是最近一轮，huge×2 是旧轮；每条按码点/4 估算
        String huge = "x".repeat(400); // 400 码点 / 4 = 100 token
        when(mapper.findLatestMessages("public", "chat-1", 20)).thenReturn(List.of(
                conversation(MessageType.ASSISTANT.getValue(), "a2"),
                conversation(MessageType.USER.getValue(), "q2"),
                conversation(MessageType.ASSISTANT.getValue(), huge),
                conversation(MessageType.USER.getValue(), huge)
        ));
        mockTokenEstimate();
        properties.setMaxHistoryTokens(120);

        List<Message> trimmed = memory.get("chat-1");

        // 最旧一轮（两条 100 token）被裁掉，历史从 q2 整轮开始
        assertThat(trimmed).extracting(Message::getText).containsExactly("q2", "a2");
    }

    @Test
    void neverStartsWithOrphanAssistantMessage() {
        // DB 倒序返回（最新在前）。预算 250：装得下旧 huge+q3+a3（102），装不下再带一条旧 huge
        // （202）——裁剪后最旧是孤儿 ASSISTANT，防护再让一条，历史从 USER 开始
        String huge = "x".repeat(400); // 100 token
        when(mapper.findLatestMessages("public", "chat-1", 20)).thenReturn(List.of(
                conversation(MessageType.ASSISTANT.getValue(), "a3"),
                conversation(MessageType.USER.getValue(), "q3"),
                conversation(MessageType.USER.getValue(), huge),
                conversation(MessageType.ASSISTANT.getValue(), huge),
                conversation(MessageType.USER.getValue(), huge)
        ));
        mockTokenEstimate();
        properties.setMaxHistoryTokens(250);

        List<Message> trimmed = memory.get("chat-1");

        assertThat(trimmed).hasSize(3);
        assertThat(trimmed.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(trimmed.get(1).getText()).isEqualTo("q3");
        assertThat(trimmed.get(2).getText()).isEqualTo("a3");
    }

    @Test
    void keepsAtLeastTheLatestTurnRegardlessOfBudget() {
        // DB 倒序返回（最新在前）。预算极小（1 token）：保底最近 1 整轮不被裁光
        when(mapper.findLatestMessages("public", "chat-1", 20)).thenReturn(List.of(
                conversation(MessageType.ASSISTANT.getValue(), "a1"),
                conversation(MessageType.USER.getValue(), "q1")
        ));
        when(costService.estimateTokens(org.mockito.ArgumentMatchers.anyString())).thenReturn(50L);
        properties.setMaxHistoryTokens(1);

        List<Message> trimmed = memory.get("chat-1");

        assertThat(trimmed).extracting(Message::getText).containsExactly("q1", "a1");
    }

    /** 估算口径与 TenantCostService 一致：码点数/4 向上取整（返回 long 对齐接口签名）。 */
    private void mockTokenEstimate() {
        when(costService.estimateTokens(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    String text = inv.getArgument(0);
                    return (long) ((text.codePointCount(0, text.length()) + 3) / 4);
                });
    }

    private Conversation conversation(String type, String text) {
        return Conversation.builder()
                .tenantId("public")
                .conversationId("chat-1")
                .type(type)
                .message(text)
                .build();
    }
}
