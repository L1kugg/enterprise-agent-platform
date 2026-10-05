package com.enterprise.iqk.agent.research;

import com.enterprise.iqk.llm.ModelCallGuard;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.service.TenantCostService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;
import org.springframework.stereotype.Component;

/**
 * 将研究发现汇总合成为结构化报告。
 */
@Component
@RequiredArgsConstructor
public class ReportWriterAgent {

    private final ChatClient chatClient;
    private final ModelRouter modelRouter;
    private final TenantCostService tenantCostService;
    private final ModelCallGuard modelCallGuard;

    /** LLM 成稿：按固定结构（摘要/关键发现/详细分析/结论建议）把研究发现汇总为中文报告。 */
    public String writeReport(String topic, String findings, String conversationId, String tenantId, String modelProfile) {
        String prompt = "Write a comprehensive research report based on the findings below.%nStructure: 1) Executive Summary 2) Key Findings 3) Detailed Analysis 4) Conclusions & Recommendations%n%nTopic: %s%n%nResearch Findings:%n%s%n".formatted(topic, findings);

        ModelRouter.ModelRouteDecision decision = modelRouter.resolve(modelProfile, "research", tenantId, topic);
        long inputTokens = tenantCostService.estimateTokens(prompt);
        tenantCostService.assertBudget(tenantId, decision.costTier(), inputTokens, 2000);

        String report = modelCallGuard.call("research-report", () -> chatClient.prompt()
                .options(ChatOptions.builder().model(decision.model()).build())
                .advisors(a -> a.param(CONVERSATION_ID, conversationId))
                .system("You are a research report writer. Produce well-structured, evidence-based reports in Chinese.")
                .user(prompt)
                .call()
                .content());

        long outputTokens = tenantCostService.estimateTokens(report);
        tenantCostService.recordUsage(tenantId, decision.costTier(), inputTokens, outputTokens, "research_writer");
        return report;
    }
}
