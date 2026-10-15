package com.enterprise.iqk.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.iqk.config.properties.ChatMemoryProperties;
import com.enterprise.iqk.domain.Conversation;
import com.enterprise.iqk.mapper.ConversationMapper;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.service.TenantCostService;
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
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
/**
 * Spring AI ChatMemory 的 MySQL 实现：消息逐条持久化到 conversation 表。
 * 读写都以 MDC 中的 tenant_id 作为隔离键（由认证过滤器写入请求线程）。
 * 窗口治理（§18.2 第一步，app.chat.memory）：读取 = 条数窗口（max-messages）
 * + Token 预算按轮裁剪（max-history-tokens，超预算从最旧整轮丢弃、保底最近 1 轮）；
 * 写入只落 user/assistant——SYSTEM 是每请求的检索上下文/记忆注入，永不做历史
 * （与 MessageChatMemoryAdvisor 不持久化 system 的行为互为双保险）。
 * 裁剪只作用于"进模型的部分"，conversation 表保持全量（审计/回放价值）。
 */
public class MysqlChatMemory implements ChatMemory {

    private final ConversationMapper conversationMapper;
    private final ChatMemoryProperties properties;
    private final TenantCostService tenantCostService;

    @Override
    /** 逐条把消息写入 conversation 表，type 列存 Spring AI 的消息角色；SYSTEM 不入历史。 */
    public void add(String conversationId, List<Message> messages) {
        String tenantId = currentTenantId();
        for (Message message : messages) {
            if (message.getMessageType() == MessageType.SYSTEM) {
                continue;
            }
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
    /** 读取窗口内历史：条数窗口（max-messages）+ Token 预算按轮裁剪。 */
    public List<Message> get(String conversationId) {
        return get(conversationId, properties.getMaxMessages());
    }

    /** 读取最近 lastN 条，翻转为时间正序后按 Token 预算从最旧整轮裁剪；不支持的消息类型跳过并告警。 */
    public List<Message> get(String conversationId, int lastN) {
        if (lastN <= 0) {
            return List.of();
        }
        List<Conversation> records = conversationMapper.findLatestMessages(currentTenantId(), conversationId, lastN);
        if (records == null || records.isEmpty()) {
            //没查到
            return new ArrayList<>();
        }

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
        // DB 倒序查询结果翻回时间正序
        java.util.Collections.reverse(messageList);
        return trimToTokenBudget(messageList);
    }

    /**
     * Token 预算裁剪（§18.2"按时间衰减丢弃"）：预算内尽量多带，超预算从最旧开始丢弃；
     * 历史不从孤儿 ASSISTANT 半轮开始；无论预算多小，保底最近 1 整轮（含其 user 起点，
     * 不足一对时有啥留啥）。估算口径与租户成本记账一致（码点/4）。
     */
    private List<Message> trimToTokenBudget(List<Message> chronological) {
        long budget = Math.max(1, properties.getMaxHistoryTokens());
        long total = 0;
        int keepFrom = 0;
        for (int i = chronological.size() - 1; i >= 0; i--) {
            total += tenantCostService.estimateTokens(chronological.get(i).getText());
            if (total > budget && i < chronological.size() - 1) {
                keepFrom = i + 1;
                break;
            }
            keepFrom = i;
        }
        // 历史不从孤儿 ASSISTANT 半轮开始：保底起点若是对上一轮回答的引用，再往后让一条
        if (keepFrom > 0
                && chronological.get(keepFrom).getMessageType() == MessageType.ASSISTANT
                && keepFrom < chronological.size() - 1) {
            keepFrom++;
        }
        // 保底最近 1 整轮：keepFrom 不越过最新一轮的 user 起点（预算再小也不裁光当前轮）
        int lastUserIdx = -1;
        for (int i = chronological.size() - 1; i >= 0; i--) {
            if (chronological.get(i).getMessageType() == MessageType.USER) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx >= 0 && keepFrom > lastUserIdx) {
            keepFrom = lastUserIdx;
        }
        if (keepFrom == 0) {
            return chronological;
        }
        log.debug("会话历史超出 Token 预算，从第 {} 条截断: dropped={}, budget={}, estimated={}",
                keepFrom, keepFrom, budget, total);
        return new ArrayList<>(chronological.subList(keepFrom, chronological.size()));
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
