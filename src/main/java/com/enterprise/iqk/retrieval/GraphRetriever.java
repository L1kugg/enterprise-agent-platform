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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识图谱检索器：从查询中提取关键词候选，在图谱中批量检索实体与事实两类证据。
 * 实体条目附带一跳邻居关系作为上下文（固定得分 0.85）；
 * 事实条目以图谱置信度作为检索得分（缺失时默认 0.7）。
 */
@Component
@RequiredArgsConstructor
public class GraphRetriever {

    /** 关键词候选总数上限（滑窗子串数量随中文长句线性增长，必须封顶）。 */
    private static final int MAX_KEYWORDS = 12;
    /** 拉丁/数字词（类名、技术名词），实体名的高价值精确候选。 */
    private static final Pattern LATIN_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_+#]*");
    /** 汉字连续串（汉字在正则里也算字母，必须单独立规则切出来）。 */
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4E00-\\u9FFF]{2,}");

    private final GraphService graphService;
    private final MeterRegistry meterRegistry;

    /**
     * 图谱检索：按关键词候选批量各取至多 topK 条实体与事实（sourceType=graph），
     * 提取不到有效关键词时返回空列表；异常上抛由混合检索层降级。
     */
    public List<ScoredDocument> retrieve(String query, String tenantId, int topK) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            List<ScoredDocument> results = new ArrayList<>();

            // 提取关键词候选（多候选批量 OR，兼容中文无分词场景）
            List<String> keywords = extractKeywords(query);
            if (keywords.isEmpty()) {
                outcome = "empty";
                return results;
            }

            // 按关键词候选批量搜索实体
            List<KgEntityRecord> entities = graphService.searchEntitiesByKeywords(tenantId, keywords, topK);
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

            // 按关键词候选批量搜索事实
            List<KgFactRecord> facts = graphService.searchFactsByKeywords(tenantId, keywords, topK);
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

    /**
     * 提取查询关键词候选，拉丁词与中文串分开处理：
     * 1) 拉丁词（类名/技术名词，如 ArrayList）：取最长的 1-3 个，实体名多为这类词；
     * 2) 中文连续串：汉字在正则里也算字母，整句中文是一个"词"，
     *    长度 ≥4 的串切 2/3/4 字滑窗子串（实体名是短词，LIKE 必须拿短词才碰得到），
     *    2-3 字的串整串入候选。
     * 候选去重后封顶 {@link #MAX_KEYWORDS}（拉丁词优先占位）。
     */
    List<String> extractKeywords(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();

        List<String> latinTokens = new ArrayList<>();
        Matcher latin = LATIN_TOKEN.matcher(query);
        while (latin.find()) {
            String token = latin.group();
            if (token.length() >= 2) {
                latinTokens.add(token);
            }
        }
        latinTokens.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String token : latinTokens.subList(0, Math.min(3, latinTokens.size()))) {
            candidates.add(token);
        }

        List<String> cjkRuns = new ArrayList<>();
        Matcher cjk = CJK_RUN.matcher(query);
        while (cjk.find()) {
            if (cjk.group().length() >= 2) {
                cjkRuns.add(cjk.group());
            }
        }
        cjkRuns.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String run : cjkRuns.subList(0, Math.min(2, cjkRuns.size()))) {
            if (candidates.size() >= MAX_KEYWORDS) {
                break;
            }
            if (run.length() >= 4) {
                for (int size = 2; size <= 4 && candidates.size() < MAX_KEYWORDS; size++) {
                    for (int i = 0; i + size <= run.length() && candidates.size() < MAX_KEYWORDS; i++) {
                        candidates.add(run.substring(i, i + size));
                    }
                }
            } else {
                candidates.add(run);
            }
        }
        return candidates.stream().limit(MAX_KEYWORDS).toList();
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
