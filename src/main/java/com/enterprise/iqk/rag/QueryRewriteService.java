package com.enterprise.iqk.rag;

import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.llm.ModelCallGuard;
import com.enterprise.iqk.llm.ModelRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 多轮查询改写（rag.query-rewrite-enabled，默认开）：多轮对话里的指代型追问
 * （"那它呢？""第二条展开讲讲"）直接拿去检索必然跑偏——本服务拉取会话历史,
 * 让 economy 档模型把追问改写成一个独立、自包含的检索查询。
 *
 * 设计要点：
 *  - 零成本短路：首轮对话（历史不足 2 条）直接返回原查询，不发 LLM 调用；
 *  - 失败兜底：改写 LLM 的任何异常（熔断/超时/解析失败）都回退原查询——
 *    改写是质量增强，不是检索的正确性依赖；
 *  - 只影响检索词,不改变传给生成模型的用户原话（生成侧模型本就带会话记忆 advisor）。
 */
@Slf4j
@Service
public class QueryRewriteService {

    private static final String SYSTEM_PROMPT =
            "你是检索查询改写器。根据对话历史,把用户最新的问题改写成一个独立、自包含的搜索查询"
                    + "(补全指代对象,保留原意与原语言)。"
                    + "只输出改写后的查询文本本身,不要解释、不要回答问题;"
                    + "如果问题本身已经独立完整,原样输出。";

    private final ChatClient agentChatClient;
    private final com.enterprise.iqk.config.MysqlChatMemory chatMemory;
    private final ModelRouter modelRouter;
    private final ModelCallGuard modelCallGuard;
    private final RagProperties ragProperties;

    public QueryRewriteService(ChatClient agentChatClient,
                               com.enterprise.iqk.config.MysqlChatMemory chatMemory,
                               ModelRouter modelRouter, ModelCallGuard modelCallGuard,
                               RagProperties ragProperties) {
        this.agentChatClient = agentChatClient;
        this.chatMemory = chatMemory;
        this.modelRouter = modelRouter;
        this.modelCallGuard = modelCallGuard;
        this.ragProperties = ragProperties;
    }

    /**
     * 把用户问题改写为独立检索查询;无历史/已禁用/任何失败均回退原查询。
     *
     * @param query          用户当前问题
     * @param conversationId 会话记忆键（与生成侧 advisor 同键）
     * @param tenantId       租户（模型路由用）
     */
    public String rewriteForRetrieval(String query, String conversationId, String tenantId) {
        if (!StringUtils.hasText(query)) {
            return query;
        }
        if (!ragProperties.isQueryRewriteEnabled() || !StringUtils.hasText(conversationId)) {
            return query;
        }
        try {
            // ChatMemory 接口只有 get(String);窗口裁剪用 MysqlChatMemory 的 lastN 重载
            List<Message> history =
                    chatMemory.get(conversationId, ragProperties.getRewriteHistoryMessages());
            // 首轮(无历史或仅当前问题)无需改写:零成本短路
            if (history == null || history.size() < 2) {
                return query;
            }
            return doRewrite(query, history, conversationId, tenantId);
        } catch (Exception ex) {
            log.warn("查询改写失败，回退原查询: query={}, reason={}", query, ex.toString());
            return query;
        }
    }

    private String doRewrite(String query, List<Message> history,
                             String conversationId, String tenantId) {
        ModelRouter.ModelRouteDecision decision =
                modelRouter.resolve("economy", "query_rewrite", tenantId, conversationId);
        String historyText = renderHistory(history);
        String userPrompt = "对话历史:\n" + historyText + "\n\n用户最新问题:" + query
                + "\n\n改写后的独立检索查询:";
        String raw = modelCallGuard.call("query_rewrite", () -> agentChatClient.prompt()
                .options(ChatOptions.builder().model(decision.model()).temperature(0.0).build())
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content());
        String rewritten = clean(raw);
        if (!StringUtils.hasText(rewritten)) {
            return query;
        }
        log.debug("查询改写: {} -> {}", query, rewritten);
        return rewritten;
    }

    /** 历史渲染:角色 + 内容（单条截断 300 字符,省 token 且够改写用）。 */
    private String renderHistory(List<Message> history) {
        StringBuilder sb = new StringBuilder();
        for (Message message : history) {
            String content = message.getText() == null ? "" : message.getText();
            if (content.length() > 300) {
                content = content.substring(0, 300);
            }
            String role = message.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER
                    ? "用户" : "助手";
            sb.append(role).append(": ").append(content.replace('\n', ' ')).append('\n');
        }
        return sb.toString();
    }

    /** 清洗模型输出:去首尾空白、包裹引号/反引号、"查询:"式前缀。 */
    private String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```$", "").trim();
        }
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            text = text.substring(1, text.length() - 1).trim();
        }
        text = text.replaceFirst("^(查询|改写后(的)?(搜索)?查询|检索查询)[:：]\\s*", "");
        return text.trim();
    }
}
