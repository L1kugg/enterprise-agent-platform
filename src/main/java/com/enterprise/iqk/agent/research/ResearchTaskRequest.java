package com.enterprise.iqk.agent.research;

import lombok.Data;

@Data
public class ResearchTaskRequest {
    private String topic;
    private String modelProfile;
    private int maxSearchRounds = 3;
    /**
     * 是否在研究工作流中启用网络搜索。
     * 默认为 false —— 需要已配置的搜索后端（SearXNG 或 Bing API），
     * 并通过 app.web-search.enabled=true 显式开启。
     */
    private boolean enableWebSearch = false;
    private boolean enableRagSearch = true;
    private boolean enableGraphSearch = true;
}
