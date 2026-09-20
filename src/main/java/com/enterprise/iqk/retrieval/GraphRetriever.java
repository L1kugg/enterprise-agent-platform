package com.enterprise.iqk.retrieval;

import com.enterprise.iqk.graph.GraphService;
import com.enterprise.iqk.graph.KgEntityRecord;
import com.enterprise.iqk.graph.KgFactRecord;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱检索器：从查询中提取主关键词，在图谱中分别检索实体与事实两类证据。
 * 实体条目附带一跳邻居关系作为上下文（固定得分 0.85）；
 * 事实条目以图谱置信度作为检索得分（缺失时默认 0.7）。
 */
@Component
@RequiredArgsConstructor
public class GraphRetriever {

    private final GraphService graphService;
    private final MeterRegistry meterRegistry;

    /**
     * 图谱检索：按主关键词各取至多 topK 条实体与事实（sourceType=graph），
     * 提取不到有效关键词时返回空列表；异常上抛由混合检索层降级。
     */
    public List<ScoredDocument> retrieve(String query, String tenantId, int topK) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            List<ScoredDocument> results = new ArrayList<>();

            // 从查询中提取关键词用于实体匹配
            String keyword = extractMainKeyword(query);
            if (!StringUtils.hasText(keyword)) {
                outcome = "empty";
                return results;
            }

            // 按关键词搜索实体
            List<KgEntityRecord> entities = graphService.searchEntities(tenantId, keyword, topK);
            for (int i = 0; i < entities.size(); i++) {
                KgEntityRecord e = entities.get(i);
                // 获取一跳邻居以丰富上下文
                List<GraphService.GraphNeighbor> neighbors = graphService.getNeighbors(tenantId, e.getEntityId());
                String neighborContext = buildNeighborContext(neighbors);

                results.add(ScoredDocument.builder()
                        .docId("graph-entity-" + i)
                        .sourceType("graph")
                        .title(e.getName() + " (" + e.getType() + ")")
                        .chunkId(e.getEntityId())
                        .content(e.getName() + ": " + defaultText(e.getDescription(), "")
                                + (StringUtils.hasText(neighborContext) ? " | " + neighborContext : ""))
                        .retrievalScore(0.85)
                        .metadata(Map.of("entityType", e.getType(), "entityId", e.getEntityId()))
                        .build());
            }

            // 按关键词搜索事实
            List<KgFactRecord> facts = graphService.searchFacts(tenantId, keyword, topK);
            for (int i = 0; i < facts.size(); i++) {
                KgFactRecord f = facts.get(i);
                results.add(ScoredDocument.builder()
                        .docId("graph-fact-" + i)
                        .sourceType("graph")
                        .title(f.getSubject() + " " + f.getPredicate() + " " + f.getObject())
                        .chunkId(f.getFactId())
                        .content(f.getSubject() + " " + f.getPredicate() + " " + f.getObject())
                        .retrievalScore(f.getConfidence() != null ? f.getConfidence() : 0.7)
                        .metadata(Map.of("factId", f.getFactId()))
                        .build());
            }

            outcome = results.isEmpty() ? "empty" : "success";
            return results;
        } finally {
            sample.stop(Timer.builder("retrieval.graph.latency")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    /** 提取查询主关键词：取最长的 token（至少 2 字符），无合适 token 时回退整句 trim */
    private String extractMainKeyword(String query) {
        if (!StringUtils.hasText(query)) return "";
        // 简单策略：取最长的 token 作为关键词
        String longest = "";
        for (String token : query.split("[^\\p{L}\\p{Nd}]+")) {
            if (token.length() > longest.length()) {
                longest = token;
            }
        }
        return longest.length() >= 2 ? longest : query.trim();
    }

    /** 邻居列表拼接为 "关系 → 实体名" 的分号分隔串，空列表返回空串 */
    private String buildNeighborContext(List<GraphService.GraphNeighbor> neighbors) {
        if (neighbors == null || neighbors.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (GraphService.GraphNeighbor n : neighbors) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(n.getRelationType()).append(" → ").append(n.getEntity().getName());
        }
        return sb.toString();
    }

    /** 文本有内容则原样返回，否则返回 fallback */
    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
