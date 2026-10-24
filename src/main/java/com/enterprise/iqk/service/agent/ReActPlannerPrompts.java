package com.enterprise.iqk.service.agent;

import com.enterprise.iqk.agent.harness.PlannerActionCatalog;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactTraceStepVO;
import com.enterprise.iqk.service.ReactResponseFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * ReAct 两条链路（ReactAgentService 对话链路 / WorkflowReactAgentService 留痕链路）共用的提示词单一来源：
 * 规划与成稿的系统提示词基座、规划用户提示词、成稿用户提示词。
 * 从两处逐字重复的实现收敛于此，新增动作或规则只改一处。
 * 中文驱动使模型 thought/answer 用中文输出，JSON 键名与动作名保持英文（解析逻辑依赖）。
 */
@Component
@RequiredArgsConstructor
public class ReActPlannerPrompts {

    /** 平台级规划器系统提示词基座（智能体角色设定由 AgentPromptMerger 附加，永不覆盖本段） */
    public static final String PLANNER_BASE_SYSTEM =
            "你是严格的 JSON ReAct 规划器，只输出合法 JSON，thought 与 answer 用简体中文。";
    /** 平台级成稿系统提示词基座 */
    public static final String FINAL_BASE_SYSTEM =
            "你是企业级AI助手，请结合轨迹和观察信息给出最终答案。答案正文不要罗列「引用来源/参考文献」清单，来源由界面单独展示。";

    /** ReAct 循环步数用尽时的成稿追加说明：让"未做完"对用户透明，而不是无声降质。 */
    public static final String STEPS_EXHAUSTED_NOTE =
            "%n%n注意：本次任务在规划步数上限内未完成全部动作，以上轨迹仅为部分进展。"
                    + "请如实汇总已获取的信息作答，不要编造未执行步骤的结果，"
                    + "并在答案末尾提醒用户：本问题较复杂，建议拆分为多个小问题分别提问，或由管理员调高该智能体的步数上限。";

    private final PlannerActionCatalog plannerActionCatalog;
    /** 绑定工具目录（R7.3）：per-agent 生成 tool_call 可用工具清单提示词段。 */
    private final AgentToolCatalog agentToolCatalog;
    private final ReactResponseFormatter responseFormatter;

    /**
     * 组装单步规划提示词：动作块（白名单）+ 绑定工具目录（per-agent，未绑定为空串零变化）
     * + 规则 + JSON 格式 + 用户问题 + 已知记忆 + 附件段（聊天临时附件，空串零变化）
     * + 滚动上下文 + 已有轨迹。
     */
    public String planningPrompt(ReactChatRequestVO request,
                                 String rollingContext,
                                 List<ReactTraceStepVO> trace,
                                 String knownMemory,
                                 String tenantId,
                                 AgentChatRuntime.RunState runState,
                                 String attachmentBlock) {
        // 绑定工具目录（R7.3）：per-agent 动态段，紧跟动作块之后；未绑定工具时为空串零变化
        String toolCatalogSection = agentToolCatalog.catalogSection(
                runState == null ? null : runState.config().agentId(), tenantId);
        return """
                你是一个企业智能助手场景的 ReAct 规划器，负责为下一步选择且仅选择一个动作。
                thought（思考）与 answer（回答）必须使用简体中文书写。
                %n
                %s
                %n
                %s
                只返回 JSON，格式如下：
                规则：即使记忆提示知识库无相关内容，也必须先执行一次 rag_search 验证（记忆可能过时）；仅当本次轨迹里的 rag_search 返回空结果或证据明显无关时，不要换关键词重试，直接 finish 并建议用户到「知识库」页上传相关文档。
                如果已知记忆中已包含用户问题的答案（如用户姓名、身份、偏好、之前结论），直接选择 finish 并引用记忆内容作答，不要执行 rag_search。
                如果用户询问你是谁/你能做什么/有什么功能，直接选择 finish 并介绍自己的能力（知识库检索问答RAG、业务数据查询、任务创建、天气查询、深度研究），不要执行 rag_search。
                {
                  "thought": "简短的中文推理",
                  "action": "从上面列表中选一个动作",
                  "action_input": {"key":"value"},
                  "answer": "仅当 action 为 finish 时提供，用中文作答"
                }
                %n
                用户问题：
                %s
                %n
                已知记忆（用户画像 / 历史对话 / 已确认事实，作为背景参考）：
                %s
                %n%s
                滚动上下文：
                %s
                %n
                已有轨迹：
                %s%n""".formatted(
                plannerActionCatalog.standardActionsBlock(),
                toolCatalogSection,
                request.getPrompt(),
                emptyIfBlank(knownMemory),
                emptyIfBlank(attachmentBlock),
                emptyIfBlank(rollingContext),
                responseFormatter.traceSummary(trace)
        );
    }

    /** 构造成稿提示词：用户问题 + 已知记忆 + 附件段 + ReAct 轨迹 + 观察上下文（记忆/附件段两链路统一注入）。 */
    public String finalPrompt(ReactChatRequestVO request,
                              List<ReactTraceStepVO> trace,
                              String rollingContext,
                              String knownMemory,
                              String attachmentBlock) {
        return """
                用户问题:
                %s
                %n
                已知记忆:
                %s
                %n%s
                ReAct轨迹:
                %s
                %n
                观察上下文:
                %s
                %n
                请输出最终中文答案，要求简洁、可执行、结构清晰。
                %n""".formatted(request.getPrompt(), emptyIfBlank(knownMemory),
                emptyIfBlank(attachmentBlock), responseFormatter.traceSummary(trace),
                emptyIfBlank(rollingContext));
    }

    private static String emptyIfBlank(String value) {
        return StringUtils.hasText(value) ? value : "";
    }
}
