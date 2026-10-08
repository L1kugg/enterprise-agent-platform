package com.enterprise.iqk.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.iqk.domain.Conversation;
import com.enterprise.iqk.mapper.ConversationMapper;
import com.enterprise.iqk.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
/**
 * Spring AI ChatMemory 的 MySQL 实现：消息逐条持久化到 conversation 表。
 * 读写都以 MDC 中的 tenant_id 作为隔离键（由认证过滤器写入请求线程）；
 * 读取按最新 N 条（默认窗口 100）倒序查询后翻转回时间正序返回。
 */
public class MysqlChatMemory implements ChatMemory {
    /** 默认记忆窗口：读取最近 100 条消息 */
    /** 默认记忆窗口：读取最近 20 条消息（上下文防爆，长对话裁旧留新） */
    private static final int DEFAULT_HISTORY_SIZE = 20;

    private final ConversationMapper conversationMapper;


    @Override
    /** 逐条把消息写入 conversation 表，type 列存 Spring AI 的消息角色。 */
    public void add(String conversationId, List<Message> messages) {
        String tenantId = currentTenantId();
        for (Message message : messages) {
            Conversation conversation = Conversation.builder()
                    .tenantId(tenantId)
                    .conversationId(conversationId)
                    .createTime(LocalDateTime.now())
                    .message(message.getText())
                    .type(message.getMessageType().getValue())//role
                    .build();
            conversationMapper.insert(conversation);
        }
    }

    @Override
    /** 读取默认窗口（最近 100 条）的会话消息。 */
    public List<Message> get(String conversationId) {
        return get(conversationId, DEFAULT_HISTORY_SIZE);
    }

    /** 读取最近 lastN 条并翻转为时间正序；不支持的消息类型跳过并告警。 */
    public List<Message> get(String conversationId, int lastN) {
        if (lastN <= 0) {
            return List.of();
        }
        List<Conversation> records = conversationMapper.findLatestMessages(currentTenantId(), conversationId, lastN);
        if (records == null || records.isEmpty()) {
            //没查到
            return new ArrayList<>();
        } else {
            //说明有数据

            List<Message> messageList = new ArrayList<>();
            for (Conversation r : records) {
                Message message = null;
                if (MessageType.USER.getValue().equals(r.getType())) {
                    message = new UserMessage(r.getMessage());
                } else if (MessageType.ASSISTANT.getValue().equals(r.getType())) {
                    message = new AssistantMessage(r.getMessage());
                } else if (MessageType.SYSTEM.getValue().equals(r.getType())) {
                    message = new SystemMessage(r.getMessage());
                }
                if (message == null) {
                    log.warn("skip unsupported chat memory message type: conversationId={}, type={}",
                            conversationId, r.getType());
                    continue;
                }
                messageList.add(message);
            }
            Collections.reverse(messageList);
            return messageList;
        }
    }

    @Override
    public void clear(String conversationId) {
        //sql：delete from conversation where conversation_id = ?
        conversationMapper.delete(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getTenantId, currentTenantId())
                .eq(Conversation::getConversationId, conversationId));
        //清空所有对话
    }

    /** 从 MDC 取当前租户并归一化，空值回落 public。 */
    private String currentTenantId() {
        return TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
    }
}
