package com.enterprise.iqk.agent.workflow;

import com.enterprise.iqk.agent.harness.PlannerActionCatalog;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactTraceStepVO;
import com.enterprise.iqk.service.ReactResponseFormatter;
import com.enterprise.iqk.service.agent.AgentChatRuntime;
import com.enterprise.iqk.service.agent.AgentToolCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 工作流引擎的规划提示词组装（从 WorkflowReactAgentService 抽出，控制主类行数）：
 * 动作块（全局）+ 绑定工具目录（R7.3，per-agent，未绑定为空）+ 规则 + 用户问题 +
 * 滚动上下文 + 已知记忆 + 已有轨迹。中文驱动使模型 thought/answer 用中文输出，
 * JSON 键名与动作名保持英文（解析逻辑依赖）。
 */
@Component
@RequiredArgsConstructor
public class WorkflowPlannerPrompts {

    private final PlannerActionCatalog plannerActionCatalog;
    private final AgentToolCatalog agentToolCatalog;
    private final ReactResponseFormatter responseFormatter;

    /** 组装单步规划提示词；绑定工具目录段由 runState 的 agentId 动态生成（未绑定为空）。 */
    public String planningPrompt(ReactChatRequestVO request,
                                 String rollingContext,
                                 List<ReactTraceStepVO> trace,
                                 String knownMemory,
                                 String tenantId,
                                 AgentChatRuntime.RunState runState) {
        String toolCatalogSection = agentToolCatalog.catalogSection(
                runState == null ? null : runState.config().agentId(), tenantId);
        return """
                你是一个企业智能助手场景的 ReAct 规划器，为下一步选择且仅选择一个动作。
                thought（思考）与 answer（回答）必须使用简体中文书写。
                %s
                %s
                规则：即使记忆提示知识库无相关内容，也必须先执行一次 rag_search 验证（记忆可能过时）；仅当本次轨迹里的 rag_search 返回空结果或证据明显无关时，不要换关键词重试，直接 finish 并建议用户到「知识库」页上传相关文档。
                如果已知记忆中已包含用户问题的答案（如用户姓名、身份、偏好、之前结论），直接选择 finish 并引用记忆内容作答，不要执行 rag_search。
                如果用户询问你是谁/你能做什么/有什么功能，直接选择 finish 并介绍自己的能力（知识库检索问答RAG、业务数据查询、任务创建、天气查询、深度研究），不要执行 rag_search。
                只返回 JSON：{"thought": "简短的中文推理", "action": "动作名", "action_input": {"key":"value"}, "answer": "仅 finish 时提供，用中文作答"}
                用户问题：
                %s
                滚动上下文：
                %s
                已知记忆（用户画像 / 近期对话 / 已确认事实，作为背景参考）：
                %s
                已有轨迹：%s""".formatted(plannerActionCatalog.workflowActionsSection(), toolCatalogSection,
                request.getPrompt(), emptyIfBlank(rollingContext), emptyIfBlank(knownMemory),
                responseFormatter.traceSummary(trace));
    }

    private static String emptyIfBlank(String value) {
        return StringUtils.hasText(value) ? value : "";
    }
}
