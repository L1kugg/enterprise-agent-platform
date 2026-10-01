package com.enterprise.iqk.agent.workflow;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ReAct 规划器的确定性降级：LLM 规划失败（异常/解析失败）时按关键词路由到
 * 预设动作，保证工作流响应仍可用。从 WorkflowReactAgentService 抽出，
 * 纯函数无状态，供同步与流式两条链路共用。
 */
final class ReactPlannerFallbacks {

    private ReactPlannerFallbacks() {
    }

    /** 动作白名单外的值一律归一到 finish（防模型输出未注册动作）。 */
    static String normalizeAction(String action) {
        return (!StringUtils.hasText(action)) ? "finish" : action.trim().toLowerCase(Locale.ROOT);
    }

    /** 按问题关键词选预设降级决策：空问题→引导补充；校区/预约/知识库→对应话术或动作；其余→通用兜底。 */
    static ReasonFallback fallbackDecision(String prompt) {
        String safe = prompt == null ? "" : prompt.trim().toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(safe)) {
            return new ReasonFallback("用户输入为空，直接引导补充。", "finish", Map.of(),
                    "当前请求内容为空，请补充问题后重试。",
                    List.of("source=fallback://input_validation, chunk=1"),
                    List.of("规则兜底：空问题时引导用户补充输入。"));
        }
        if (containsAny(safe, "校区", "campus")) {
            return new ReasonFallback("识别到校区相关问题，走校区查询兜底流程。", "finish", Map.of(),
                    "已识别为校区查询请求：可以返回校区列表，并按城市或课程类型做进一步筛选。",
                    List.of("source=fallback://school_query_flow, chunk=1"),
                    List.of("校区查询流程：先列出校区，再按城市/课程类型筛选。"));
        }
        if (containsAny(safe, "课程预约", "预约字段", "预约需要", "联系方式", "姓名")) {
            return new ReasonFallback("识别到课程预约相关需求，返回预约字段模板。", "finish", Map.of(),
                    "课程预约建议至少包含：课程、姓名、联系方式、校区。",
                    List.of("source=fallback://course_reservation_schema, chunk=1"),
                    List.of("预约字段模板。"));
        }
        if (containsAny(safe, "知识库", "引用", "来源", "pdf", "文档", "source")) {
            return new ReasonFallback("识别到知识库/引用相关需求，转知识库检索。", "rag_search", Map.of("query", prompt),
                    "", List.of(), List.of());
        }
        return new ReasonFallback("规划器暂不可用，走通用兜底。", "finish", Map.of(),
                "当前规划器暂不可用，建议稍后重试或细化问题关键词。",
                List.of("source=fallback://planner_unavailable, chunk=1"),
                List.of("系统兜底。"));
    }

    private static boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text) || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 降级决策载体，字段与编排层的 ReasonDecision 对齐。 */
    record ReasonFallback(String thought, String action, Map<String, Object> actionInput,
                          String answer, List<String> citations, List<String> evidence) {
    }
}
