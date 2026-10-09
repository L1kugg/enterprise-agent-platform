package com.enterprise.iqk.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆注入 Advisor：在请求组装期（before）把召回的记忆快照作为一条独立
 * SystemMessage 插入 prompt 首部。不改写 user 消息文本，因此
 * MessageChatMemoryAdvisor 持久化的仍是原始对话 —— 会话历史不会逐轮
 * 累积记忆快照（"把记忆拼进 user prompt"方案的固有问题：增强消息被存进
 * 历史并在后续轮次重放，第 N 轮 prompt 里堆 N 份记忆）。每次调用重新
 * 召回，注入的永远是最新的唯一一份。
 *
 * 显式 opt-in：调用方通过 advisor 参数传 MEMORY_TENANT_KEY 与
 * MEMORY_USER_KEY 才注入；未传参的链路（react / 评测 / 画像提取等
 * 自行管理记忆的调用）零影响。默认只注 long/fact 跨会话视图
 * （挂 ChatMemory 的链路 short 层由 advisor 保真注入，防同信息双份），
 * 链路显式传 MEMORY_INCLUDE_SHORT_KEY=true 时才带上 short 层。
 * 任何召回失败静默透传，绝不影响生成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryInjectionAdvisor implements BaseAdvisor {

    /** advisor 参数：租户 ID（与 user 键同时必传才注入） */
    public static final String MEMORY_TENANT_KEY = "memory.tenantId";
    /** advisor 参数：记忆 user 键（与租户 ID 同时必传才注入） */
    public static final String MEMORY_USER_KEY = "memory.userId";
    /** advisor 参数：是否带上 short 层（默认 false） */
    public static final String MEMORY_INCLUDE_SHORT_KEY = "memory.includeShort";

    private final MemoryService memoryService;

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Object tenantId = request.context().get(MEMORY_TENANT_KEY);
        Object userKey = request.context().get(MEMORY_USER_KEY);
        if (tenantId == null || userKey == null || !StringUtils.hasText(String.valueOf(userKey))) {
            return request;
        }
        try {
            boolean includeShort = Boolean.parseBoolean(
                    String.valueOf(request.context().getOrDefault(MEMORY_INCLUDE_SHORT_KEY, "false")));
            MemoryService.MemoryContextSnapshot snapshot = memoryService.buildContext(
                    String.valueOf(tenantId), String.valueOf(userKey), currentUserQuery(request),
                    includeShort);
            if (snapshot == null || !StringUtils.hasText(snapshot.contextText())) {
                return request;
            }
            List<Message> messages = new ArrayList<>(request.prompt().getInstructions());
            messages.add(0, new SystemMessage("已知记忆:\n" + snapshot.contextText().trim()));
            Prompt enriched = new Prompt(messages, request.prompt().getOptions());
            return request.mutate().prompt(enriched).context(request.context()).build();
        } catch (Exception ex) {
            log.warn("记忆注入失败（不影响生成）: user={}, reason={}", userKey, ex.toString());
            return request;
        }
    }

    private String currentUserQuery(ChatClientRequest request) {
        return request.prompt().getInstructions().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .reduce((first, second) -> second)
                .map(Message::getText)
                .orElse("");
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public String getName() {
        return MemoryInjectionAdvisor.class.getSimpleName();
    }
}
