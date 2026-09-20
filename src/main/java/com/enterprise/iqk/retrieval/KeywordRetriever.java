package com.enterprise.iqk.retrieval;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于关键词的检索器，对文档标题和内容进行文本重叠度评分。
 * 与 VectorRetriever 互补，可捕获语义搜索可能遗漏的精确词匹配。
 */
@Component
@RequiredArgsConstructor
public class KeywordRetriever {

    private final VectorStore vectorStore;
    private final MeterRegistry meterRegistry;

    /**
     * 关键词检索：借向量库按租户/会话拉取候选（max(topK×2, 20) 条，相似度阈值 0.25），
     * 再按查询词与标题/内容的重叠度重打分（标题 0.6 + 内容 0.4），
     * 仅保留得分 &gt; 0.05 的条目并截断到 topK；异常上抛由混合检索层降级。
     */
    public List<ScoredDocument> retrieve(String query, String tenantId, String chatId, int topK) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            // 用向量库作为文档来源，再按关键词重叠度重新排序
            String filter = "tenant_id == \"" + escapeFilter(tenantId)
                    + "\" && chat_id == \"" + escapeFilter(chatId) + "\"";
            List<Document> docs = vectorStore.similaritySearch(
                    org.springframework.ai.vectorstore.SearchRequest.builder()
                            .query(query)
                            .topK(Math.max(topK * 2, 20))
                            .similarityThreshold(0.25)
                            .filterExpression(filter)
                            .build());

            Set<String> queryTokens = tokenize(query);
            if (queryTokens.isEmpty()) {
                outcome = "empty";
                return List.of();
            }

            List<ScoredDocument> results = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) {
                Document d = docs.get(i);
                Set<String> titleTokens = tokenize(metaStr(d, "file_name", ""));
                Set<String> contentTokens = tokenize(d.getFormattedContent());

                double titleOverlap = overlapScore(queryTokens, titleTokens);
                double contentOverlap = overlapScore(queryTokens, contentTokens);
                double score = titleOverlap * 0.6 + contentOverlap * 0.4;

                if (score > 0.05) {
                    results.add(ScoredDocument.builder()
                            .docId("kw-" + i)
                            .sourceType("keyword")
                            .title(metaStr(d, "file_name", "unknown"))
                            .chunkId("chunk-" + metaStr(d, "chunk_index", String.valueOf(i)))
                            .content(d.getFormattedContent())
                            .retrievalScore(score)
                            .metadata(d.getMetadata())
                            .build());
                }
            }
            results.sort(Comparator.comparingDouble(ScoredDocument::getRetrievalScore).reversed());
            outcome = results.isEmpty() ? "empty" : "success";
            return results.stream().limit(topK).collect(Collectors.toList());
        } finally {
            sample.stop(Timer.builder("retrieval.keyword.latency")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    /** 查询 token 在目标 token 集中的命中占比（目标为空记 0） */
    private double overlapScore(Set<String> query, Set<String> target) {
        if (target.isEmpty()) return 0.0;
        long overlap = query.stream().filter(target::contains).count();
        return (double) overlap / target.size();
    }

    /** 小写化后按非字母数字字符切分为 token 集合，空白文本返回空集 */
    private Set<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) return Set.of();
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+"))
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    /** 读取文档元数据并转字符串，缺失时返回 fallback */
    private String metaStr(Document d, String key, String fallback) {
        Object v = d.getMetadata().get(key);
        return v == null ? fallback : v.toString();
    }

    /** 转义过滤表达式值中的反斜杠与双引号，防止突破过滤条件注入额外谓词 */
    private String escapeFilter(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
