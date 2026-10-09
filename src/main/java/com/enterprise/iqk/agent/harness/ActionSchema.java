package com.enterprise.iqk.agent.harness;

import java.util.Set;

/**
 * 动作 schema：声明一个 Agent 动作的元数据契约。在 Agent 里，动作通常就是一次 tool call。
 * 是策略守卫校验输入、事件记录标注风险等级、载荷脱敏的统一依据。
 */
public record ActionSchema(
        /** 动作名（小写，如 rag_search / workspace_read_file） */
        String action,
        /** 归属的 runtime（builtin / mcp / workspace），用于分发 */
        String runtime,
        /** 必填字段名集合，缺失即被策略守卫拒绝 */
        Set<String> requiredFields,
        /** 可选字段名集合，出现集合外的字段会被拒绝（防参数注入） */
        Set<String> optionalFields,
        /** 敏感字段名集合，落事件日志前会被脱敏为 [REDACTED] */
        Set<String> sensitiveFields,
        /** 风险等级（read / write / write_preview / shell / external） */
        String riskLevel,
        /** 是否仅允许受信运行时执行（需走 token 预览确认流程） */
        boolean trustedOnly,
        /** 给 ReAct 规划器提示词的动作说明（含参数示例），空 = 提示词里只出现裸动作名 */
        String plannerHint
) {
    /** 7 参便利构造器：plannerHint 置空（提示词里只出现裸动作名） */
    public ActionSchema(String action, String runtime, Set<String> requiredFields, Set<String> optionalFields,
                        Set<String> sensitiveFields, String riskLevel, boolean trustedOnly) {
        this(action, runtime, requiredFields, optionalFields, sensitiveFields, riskLevel, trustedOnly, null);
    }

    /** 字段是否在 schema 声明的必填或可选范围内 */
    public boolean knowsField(String field) {
        return requiredFields.contains(field) || optionalFields.contains(field);
    }
}
