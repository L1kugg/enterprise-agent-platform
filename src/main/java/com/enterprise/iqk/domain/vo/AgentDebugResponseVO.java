package com.enterprise.iqk.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 智能体调试面板响应：执行结论 + 模型/安全/记忆三段摘要 + 精简步骤列表。
 *  刻意不含轨迹原文（thought/actionInput/observation），调试面板只看执行概况。 */
@Data
@Builder
public class AgentDebugResponseVO {
    /** SUCCESS | BLOCKED | ERROR */
    private String status;
    private String answer;
    private String error;
    private Long latencyMs;

    private ModelSummary modelSummary;
    private SafetyResult safetyResult;
    private MemorySummary memorySummary;
    /** 知识库召回摘要（FR-R5-03）：绑定范围 + 各步骤引用，验证"只在绑定范围内召回" */
    private KnowledgeSummary knowledgeSummary;
    private List<DebugStep> steps;

    @Data
    @Builder
    public static class ModelSummary {
        private String modelName;
        /** bound = 智能体绑定模型服务；router = 档位路由 */
        private String source;
        private Boolean fallbackApplied;
        private String routeProfile;
        private String routeReason;
        private String routeCostTier;
    }

    @Data
    @Builder
    public static class SafetyResult {
        private Boolean checked;
        private Boolean blocked;
        private String blockMessage;
    }

    @Data
    @Builder
    public static class MemorySummary {
        private Integer memoryTurns;
        private List<String> injectedLabels;
    }

    /** 知识库召回摘要：scoped=true 表示召回已严格限定在绑定知识库内（FR-R5-01）。 */
    @Data
    @Builder
    public static class KnowledgeSummary {
        /** 是否处于圈定范围模式（绑定了知识库） */
        private Boolean scoped;
        /** 绑定的知识库 ID 列表 */
        private List<Long> knowledgeBaseIds;
        /** 绑定的知识库名称列表（与 ID 同序） */
        private List<String> knowledgeBaseNames;
        /** 本次调试命中的知识库 ID 去重集合（引用溯源，来自切片元数据） */
        private List<Long> recalledKnowledgeBaseIds;
    }

    @Data
    @Builder
    public static class DebugStep {
        private Integer step;
        private String action;
        private Long elapsedMs;
        private String status;
        /** rag_search 步骤的引用列表（"source=..., chunk=..." 文本），其他动作为空 */
        private List<String> citations;
    }
}
