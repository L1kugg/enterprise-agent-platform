package com.enterprise.iqk.agent.research;

import com.enterprise.iqk.llm.ModelCallGuard;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.service.TenantCostService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 将研究主题拆解为若干子问题，以便并行或逐个调查。
 */
@Component
@RequiredArgsConstructor
public class ResearchPlannerAgent {

    private final ChatClient chatClient;
    private final ModelRouter modelRouter;
    private final TenantCostService tenantCostService;
    private final ObjectMapper objectMapper;
    private final ModelCallGuard modelCallGuard;

    /** LLM 拆题：把主题分解为 3-5 个子问题+关键词；priorFindings 注入租户内早前任务结论供参考（可传空串）；解析失败或结果为空时降级为原主题单问（strategy=direct）。 */
    public ResearchPlan plan(String topic, String priorFindings, String conversationId, String tenantId, String modelProfile) {
        String prompt = "Decompose the following research topic into 3-5 sub-questions.%nReturn JSON only:%n{%n  \"subQuestions\": [\"q1\", \"q2\", ...],%n  \"keywords\": [\"kw1\", \"kw2\", ...],%n  \"strategy\": \"breadth_first\"%n}%n%nTopic: %s%n%nPrior research findings from earlier tasks in this tenant (may be empty, use to avoid duplicating settled questions):%n%s%n".formatted(topic, priorFindings);

        ModelRouter.ModelRouteDecision decision = modelRouter.resolve(modelProfile, "research", tenantId, topic);
        long inputTokens = tenantCostService.estimateTokens(prompt);
        tenantCostService.assertBudget(tenantId, decision.costTier(), inputTokens, 600);

        String raw = modelCallGuard.call("research-plan", () -> chatClient.prompt()
                .options(ChatOptions.builder().model(decision.model()).build())
                .advisors(a -> a.param(CONVERSATION_ID, conversationId))
                .system("You are a research planner. Decompose complex topics into sub-questions. Return JSON only.")
                .user(prompt)
                .call()
                .content());

        long outputTokens = tenantCostService.estimateTokens(raw);
        tenantCostService.recordUsage(tenantId, decision.costTier(), inputTokens, outputTokens, "research_planner");

        try {
            String json = extractJson(raw);
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            List<String> subQuestions = toStringList(parsed.get("subQuestions"));
            if (subQuestions.isEmpty()) {
                subQuestions = List.of(topic);
            }
            List<String> keywords = toStringList(parsed.get("keywords"));
            String strategy = parsed.get("strategy") instanceof String s ? s : "breadth_first";
            return new ResearchPlan(subQuestions, keywords, strategy);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return new ResearchPlan(List.of(topic), List.of(), "direct");
        }
    }

    /**
     * 防御性提取：模型可能返回非字符串的列表元素（数字、嵌套对象），
     * 不加处理的话后面会抛出 ClassCastException。
     */
    private static List<String> toStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(String::valueOf)
                .filter(StringUtils::hasText)
                .toList();
    }

    /** 从模型输出中截取首个 {...} JSON 块；无花括号时返回 "{}" 由调用方走降级 */
    private String extractJson(String raw) {
        if (!StringUtils.hasText(raw)) return "{}";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start < 0 || end <= start) ? "{}" : raw.substring(start, end + 1);
    }

    /** 拆题结果：子问题列表、检索关键词、检索策略（如 breadth_first / direct） */
    public record ResearchPlan(List<String> subQuestions, List<String> keywords, String strategy) {}
}
