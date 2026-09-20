package com.enterprise.iqk.retrieval.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * SearXNG 自托管搜索后端。
 * <p>
 * 需要一个已启用 JSON 格式输出的 SearXNG 实例。
 * Docker 快速启动：
 * <pre>
 * docker run -d --name searxng -p 8888:8080 \
 *   -e SEARXNG_BASE_URL=http://localhost:8888/ \
 *   searxng/searxng:latest
 * </pre>
 */
@Slf4j
@Component
public class SearXNGBackend implements WebSearchBackend {

    private final WebSearchProperties properties;
    private final ObjectMapper objectMapper;
    // volatile + 双重检查初始化：参见 BingSearchBackend。
    private volatile RestTemplate restTemplate;

    public SearXNGBackend(WebSearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 懒初始化带超时配置的 RestTemplate（双重检查锁，仅首次调用构建） */
    private RestTemplate getRestTemplate() {
        RestTemplate local = restTemplate;
        if (local == null) {
            synchronized (this) {
                local = restTemplate;
                if (local == null) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(properties.getConnectTimeoutMs());
                    factory.setReadTimeout(properties.getReadTimeoutMs());
                    local = new RestTemplate(factory);
                    restTemplate = local;
                }
            }
        }
        return local;
    }

    /** 调用 SearXNG JSON 接口搜索；未启用、空响应或请求异常均返回空列表，不抛异常 */
    @Override
    public List<WebSearchResult> search(String query, int maxResults) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        try {
            String url = properties.getSearxngUrl() + "/search?q={query}&format=json&categories=general&pageno=1";
            Map<String, String> uriVars = Map.of("query", query);

            ResponseEntity<String> response = getRestTemplate().getForEntity(url, String.class, uriVars);
            if (response.getBody() == null) {
                return Collections.emptyList();
            }

            Map<String, Object> body = objectMapper.readValue(response.getBody(), new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) body.getOrDefault("results", Collections.emptyList());

            List<WebSearchResult> searchResults = new ArrayList<>();
            for (int i = 0; i < Math.min(results.size(), maxResults); i++) {
                Map<String, Object> r = results.get(i);
                searchResults.add(WebSearchResult.builder()
                        .title((String) r.getOrDefault("title", ""))
                        .url((String) r.getOrDefault("url", ""))
                        .snippet((String) r.getOrDefault("content", ""))
                        .score(1.0 - (i * 0.1)) // 基于排名的评分
                        .build());
            }
            return searchResults;
        } catch (Exception e) {
            log.error("SearXNG search failed for query '{}': {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 后端类型为 searxng 且已配置实例 URL 时可用 */
    @Override
    public boolean isAvailable() {
        return "searxng".equalsIgnoreCase(properties.getBackend())
                && properties.getSearxngUrl() != null
                && !properties.getSearxngUrl().isBlank();
    }
}
