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

    @Data
    @Builder
    public static class DebugStep {
        private Integer step;
        private String action;
        private Long elapsedMs;
        private String status;
    }
}
