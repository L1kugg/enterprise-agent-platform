package com.enterprise.iqk.agent.research;

import lombok.Data;

/** 深度研究任务创建请求（POST /ai/research/tasks 的请求体）。 */
@Data
public class ResearchTaskRequest {
    /** 研究主题 */
    private String topic;
    /** 模型档位（可空，走默认路由） */
    private String modelProfile;
    /** 最大搜索轮数（默认 3） */
    private int maxSearchRounds = 3;
    /**
     * 是否在研究工作流中启用网络搜索。
     * 默认为 false —— 需要已配置的搜索后端（SearXNG 或 Bing API），
     * 并通过 app.web-search.enabled=true 显式开启。
     */
    private boolean enableWebSearch = false;
    /** 是否启用 RAG 知识库检索（默认 true） */
    private boolean enableRagSearch = true;
    /** 是否启用图谱检索（默认 true） */
    private boolean enableGraphSearch = true;
}
