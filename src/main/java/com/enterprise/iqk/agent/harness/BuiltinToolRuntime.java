package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.domain.query.CourseQuery;
import com.enterprise.iqk.rag.HybridRagAnswerService;
import com.enterprise.iqk.retrieval.CitationItem;
import com.enterprise.iqk.retrieval.EvidenceItem;
import com.enterprise.iqk.tools.CourseTools;
import com.enterprise.iqk.util.ConversationIdHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 进程内工具运行时：处理 4 个业务内置动作
 * （query_school / query_course / add_course_reservation / rag_search），
 * 直接复用 CourseTools 与 HybridRagAnswerService（四路混合检索），不经过模型工具调用协议。
 */
@Component
@RequiredArgsConstructor
public class BuiltinToolRuntime implements AgentRuntime {
    private static final Set<String> SUPPORTED_ACTIONS = Set.of(
            "query_school",
            "query_course",
            "add_course_reservation",
            "rag_search"
    );

    private final CourseTools courseTools;
    private final HybridRagAnswerService hybridRagAnswerService;

    @Override
    public String source() {
        return "builtin";
    }

    @Override
    public boolean supports(String action) {
        return SUPPORTED_ACTIONS.contains(action);
    }

    /** 按动作分发；下游返回 status=error 的 Map 时统一转为 error 观测 */
    @Override
    public AgentObservation execute(AgentAction action) {
        long startedNs = System.nanoTime();
        Object payload = switch (action.action()) {
            case "query_school" -> courseTools.querySchool();
            case "query_course" -> courseTools.queryCourse(toCourseQuery(action.actionInput()));
            case "add_course_reservation" -> executeReservation(action.actionInput());
            case "rag_search" -> executeRagSearch(action);
            default -> Map.of("status", "error", "message", "unsupported action: " + action.action());
        };
        if (payload instanceof Map<?, ?> mapPayload && "error".equals(mapPayload.get("status"))) {
            Object message = mapPayload.get("message");
            return AgentObservation.error(source(), message == null ? "action failed" : String.valueOf(message),
                    elapsedMs(startedNs));
        }
        return AgentObservation.success(source(), payload, elapsedMs(startedNs));
    }

    /** 预约登记：course/studentName/contactInfo/school 四项必填，缺一返回 error；成功返回 reservationId */
    private Map<String, Object> executeReservation(Map<String, Object> actionInput) {
        String course = stringVal(actionInput, "course", "");
        String studentName = stringVal(actionInput, "studentName", "");
        String contactInfo = stringVal(actionInput, "contactInfo", "");
        String school = stringVal(actionInput, "school", "");
        String remark = stringVal(actionInput, "remark", "");

        if (!StringUtils.hasText(course)
                || !StringUtils.hasText(studentName)
                || !StringUtils.hasText(contactInfo)
                || !StringUtils.hasText(school)) {
            return Map.of(
                    "status", "error",
                    "message", "missing required fields for reservation"
            );
        }

        String reservationId = courseTools.addCourseReservation(
                course,
                studentName,
                contactInfo,
                school,
                remark
        );
        return Map.of(
                "status", "created",
                "reservationId", reservationId
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
        // 当次实际生效的召回路权重（四路归一化值）：前端轨迹条照此绘制
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

    /** 动作输入 → 课程查询对象：解析 type/edu/sorts（排序字段+升降序），解析不了的字段静默忽略 */
    private CourseQuery toCourseQuery(Map<String, Object> actionInput) {
        CourseQuery query = new CourseQuery();
        query.setType(stringVal(actionInput, "type", null));
        query.setEdu(intVal(actionInput.get("edu")));

        Object sortsObj = actionInput.get("sorts");
        if (sortsObj instanceof List<?> list && !list.isEmpty()) {
            List<CourseQuery.Sort> sorts = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> rawSort)) {
                    continue;
                }
                CourseQuery.Sort sort = new CourseQuery.Sort();
                Object field = rawSort.get("field");
                Object isAsc = rawSort.get("isAsc");
                sort.setField(field == null ? null : String.valueOf(field));
                sort.setIsAsc(isAsc == null ? null : Boolean.parseBoolean(String.valueOf(isAsc)));
                sorts.add(sort);
            }
            query.setSorts(sorts);
        }
        return query;
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

    /** 取整型字段：解析失败返回 null（不抛异常） */
    private Integer intVal(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 纳秒起点换算毫秒耗时 */
    private long elapsedMs(long startedNs) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
    }
}
