package com.enterprise.iqk.retrieval.web;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 外部网络搜索后端（SearXNG 或 Bing API）的配置。
 * 网络搜索默认关闭，必须显式配置一个搜索后端。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.web-search")
public class WebSearchProperties {

    /**
     * 是否启用网络搜索，默认为 false。
     * 必须显式设为 true 并配置后端 URL，网络检索才能工作。
     */
    private boolean enabled = false;

    /**
     * 后端类型："searxng"（默认）或 "bing"。
     */
    private String backend = "searxng";

    /**
     * SearXNG 实例的基础 URL（例如 http://localhost:8888）。
     */
    private String searxngUrl = "";

    /**
     * Bing Search API v7 的订阅密钥。
     */
    private String bingApiKey = "";

    /**
     * Bing Search API v7 端点（例如 https://api.bing.microsoft.com/v7.0）。
     */
    private String bingEndpoint = "https://api.bing.microsoft.com/v7.0";

    /**
     * 连接超时时间，单位毫秒。
     */
    private int connectTimeoutMs = 3000;

    /**
     * 读取超时时间，单位毫秒。
     */
    private int readTimeoutMs = 8000;

    /**
     * 向后端请求的搜索结果最大数量。
     */
    private int maxResults = 5;
}
