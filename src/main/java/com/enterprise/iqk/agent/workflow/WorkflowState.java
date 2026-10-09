package com.enterprise.iqk.agent.workflow;

/**
 * 工作流状态机枚举：定义任务从创建到终态的全部状态与合法转移。
 * 主线 CREATED→PLANNING→SEARCHING→RETRIEVING→JUDGING→REFLECTING→WRITING→DONE，
 * RETRIEVING 亦可直接转 WRITING（深度研究检索完直接成稿，跳过评证/反思），
 * 各非终态均可转 FAILED；REFLECTING 可转 NEED_MORE_EVIDENCE 后回到检索补证。
 * 引擎层零业务智能：本枚举只守卫"怎么转"，不决定"转去哪"（编排层职责）。
 * 已知边界：NEED_MORE_EVIDENCE 转移边已定义但当前编排层零使用。
 */
public enum WorkflowState {
    /** 已创建（startTask 落库后的初始状态） */
    CREATED,
    /** 拆题规划中 */
    PLANNING,
    /** 搜索中（定位候选来源） */
    SEARCHING,
    /** 取证中（拉取文档内容） */
    RETRIEVING,
    /** 评证中（证据质量判定；当前主要作为 ReAct 轮次进度标签，非语义判定） */
    JUDGING,
    /** 反思中（评估证据充分性；当前主要作为 ReAct 轮次进度标签，非语义判定） */
    REFLECTING,
    /** 成稿中 */
    WRITING,
    /** 成功终态 */
    DONE,
    /** 证据不足待补证（转移边已定义，暂无编排使用） */
    NEED_MORE_EVIDENCE,
    /** 失败终态 */
    FAILED;

    /** 是否终态（DONE / FAILED） */
    public boolean isTerminal() {
        return this == DONE || this == FAILED;
    }

    /** ReAct 动作→状态：rag_search=SEARCHING；数据/工具查询=RETRIEVING；finish/写入=WRITING */
    public static WorkflowState forAction(String action) {
        return switch (action == null ? "finish" : action) {
            case "rag_search" -> SEARCHING;
            case "query_database", "mcp_call" -> RETRIEVING;
            default -> WRITING;
        };
    }
    /** 守卫：当前状态是否允许转移到 target（终态不允许再转移） */
    public boolean canTransitionTo(WorkflowState target) {
        return switch (this) {
            case CREATED -> target == PLANNING;
            case PLANNING -> target == SEARCHING || target == RETRIEVING || target == WRITING || target == FAILED;
            // ReAct 动作顺序非确定：连续同类动作（如两次 rag_search）保持原状态，
            // 搜索后可直接成稿（finish）；检索类动作间可来回切换，不必经过固定轮次。
            case SEARCHING -> target == SEARCHING || target == RETRIEVING || target == JUDGING
                    || target == WRITING || target == FAILED;
            case RETRIEVING -> target == SEARCHING || target == RETRIEVING || target == JUDGING
                    || target == REFLECTING || target == WRITING || target == FAILED;
            case JUDGING -> target == REFLECTING || target == WRITING || target == FAILED;
            case REFLECTING -> target == WRITING || target == NEED_MORE_EVIDENCE || target == FAILED;
            case NEED_MORE_EVIDENCE -> target == SEARCHING || target == RETRIEVING || target == FAILED;
            case WRITING -> target == WRITING || target == DONE || target == FAILED;
            case DONE, FAILED -> false;
        };
    }
}
