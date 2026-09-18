package com.enterprise.iqk.retrieval.web;

import java.util.List;

/**
 * 网络搜索后端（SearXNG、Bing 等）的抽象接口。
 */
public interface WebSearchBackend {

    /**
     * 根据给定查询进行网络搜索并返回结果。
     *
     * @param query      搜索查询
     * @param maxResults 返回结果的最大数量
     * @return 搜索结果列表，永不为 null
     */
    List<WebSearchResult> search(String query, int maxResults);

    /**
     * 检查该后端是否已配置且可用。
     */
    boolean isAvailable();
}
