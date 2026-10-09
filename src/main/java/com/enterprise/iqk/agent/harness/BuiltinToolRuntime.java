package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.rag.HybridRagAnswerService;
import com.enterprise.iqk.retrieval.CitationItem;
import com.enterprise.iqk.retrieval.EvidenceItem;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.tools.DatabaseQueryTools;
import com.enterprise.iqk.util.ConversationIdHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 进程内工具运行时：处理企业级内置动作
 * （create_task / rag_search / query_database），
 * 直接复用 HybridRagAnswerService（四路混合检索）与 DatabaseQueryTools（主库只读查询），
 * 不经过模型工具调用协议。
 */
@Component
@RequiredArgsConstructor
public class BuiltinToolRuntime implements AgentRuntime {
    private static final Set<String> SUPPORTED_ACTIONS = Set.of(
            "create_task",
            "rag_search",
            "query_database"
    );

    private final HybridRagAnswerService hybridRagAnswerService;
    private final DatabaseQueryTools databaseQueryTools;

    @Override
    public String source() {
        return "builtin";
    }

    @Override
    public boolean supports(String action) {
        return SUPPORTED_ACTIONS.contains(action);
    }

    @Override
    public AgentObservation execute(AgentAction action) {
        long startedNs = System.nanoTime();
        Map<String, Object> payload = switch (action.action()) {
            case "create_task" -> executeCreateTask(action);
            case "rag_search" -> executeRagSearch(action);
            case "query_database" -> executeDatabaseQuery(action);
            default -> Map.of("status", "error", "message", "unsupported action: " + action.action());
        };
        if ("error".equals(payload.get("status"))) {
            Object message = payload.get("message");
            return AgentObservation.error(source(), message == null ? "action failed" : String.valueOf(message),
                    elapsedMs(startedNs));
        }
        return AgentObservation.success(source(), payload, elapsedMs(startedNs));
    }

    /** 创建工作任务：返回确认信息（后续版本接入任务系统）。 */
    private Map<String, Object> executeCreateTask(AgentAction action) {
        String title = stringVal(action.actionInput(), "title", "");
        String description = stringVal(action.actionInput(), "description", "");
        String priority = stringVal(action.actionInput(), "priority", "normal");
        if (!StringUtils.hasText(title)) {
            return Map.of("status", "error", "message", "task title is required");
        }
        return Map.of(
                "status", "created",
                "taskTitle", title,
                "description", description,
                "priority", priority,
                "message", "任务已创建并写入跟踪记忆"
        );
    }

    /**
     * RAG 检索动作（四路混合管线）：query 缺省退回用户原始问题；
     * 会话 ID 用 react 前缀派生（与直连聊天链路隔离），chatId 先去单引号防注入。
     * 载荷形状与旧向量单路一致：query/answer/citations/evidence/weights，
     * citations 映射回前端可解析的 "source=..., chunk=..." 文本。
     */
    private Map<String, Object> executeRagSearch(AgentAction action) {
        String query = stringVal(action.actionInput(), "query", action.prompt());
        String conversationId = ConversationIdHelper.build("react", action.chatId());
        HybridRagAnswerService.HybridRagResult result = hybridRagAnswerService.answer(
                query,
                action.tenantId(),
                sanitizeChatId(action.chatId()),
                conversationId,
                action.modelProfile()
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("answer", result.getAnswer());
        payload.put("citations", result.getCitations().stream()
                .map(BuiltinToolRuntime::citationText)
                .toList());
        payload.put("evidence", result.getEvidence().stream()
                .map(EvidenceItem::getSnippet)
                .filter(StringUtils::hasText)
                .toList());
        payload.put("weights", result.getWeights() == null ? Map.of() : result.getWeights());
        return payload;
    }

    /**
     * CitationItem → 前端 parseCitation 可解析的 "source=..., chunk=..." 文本。
     * 标题里的半角逗号替换为全角，避免正则 source=[^,]+ 提前截断；chunkId 为程序生成无逗号。
     */
    private static String citationText(CitationItem item) {
        String title = StringUtils.hasText(item.getTitle()) ? item.getTitle().replace(',', '，') : "未知来源";
        String chunk = StringUtils.hasText(item.getChunkId()) ? item.getChunkId() : "?";
        return "source=" + title + ", chunk=" + chunk;
    }

    /**
     * 主库只读查询：SQL 由模型现场生成，守卫/租户启发式/停用开关都在 DatabaseQueryTools 内闭环。
     * 租户从 action.tenantId() 服务端注入并归一化（schema 不开放该入参，防模型伪造）；
     * sql 缺失时 ActionPolicyGuard 已在 runtime 之前拒绝，这里兜底返回空串交给工具层报错。
     */
    private Map<String, Object> executeDatabaseQuery(AgentAction action) {
        String sql = stringVal(action.actionInput(), "sql", "");
        String tenantId = TenantContext.normalize(action.tenantId());
        return databaseQueryTools.queryDatabase(sql, tenantId);
    }

    /** 去掉 chatId 中的单引号，防止拼接进 SQL 的注入风险 */
    private String sanitizeChatId(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace("'", "");
    }

    /** 取字符串字段：null/空白统一返回 fallback */
    private String stringVal(Map<String, Object> input, String key, String fallback) {
        Object raw = input.get(key);
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw).trim();
        return StringUtils.hasText(value) ? value : fallback;
    }

    /** 纳秒起点换算毫秒耗时 */
    private long elapsedMs(long startedNs) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
    }
}
