package com.enterprise.iqk.retrieval;

import com.enterprise.iqk.config.properties.RagProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class VectorRetriever {

    private final VectorStore vectorStore;
    private final RagProperties ragProperties;
    private final MeterRegistry meterRegistry;

    public List<ScoredDocument> retrieve(String query, String tenantId, String chatId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            String filter = "tenant_id == \"" + escapeFilter(tenantId)
                    + "\" && chat_id == \"" + escapeFilter(chatId) + "\"";
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(ragProperties.getRetrieveTopK())
                    .similarityThreshold(ragProperties.getSimilarityThreshold())
                    .filterExpression(filter)
                    .build();
            List<Document> docs = vectorStore.similaritySearch(request);
            outcome = docs.isEmpty() ? "empty" : "success";
            List<ScoredDocument> results = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) {
                Document d = docs.get(i);
                results.add(ScoredDocument.builder()
                        .docId("vec-" + i)
                        .sourceType("vector")
                        .title(metaStr(d, "file_name", "unknown"))
                        .chunkId("chunk-" + metaStr(d, "chunk_index", String.valueOf(i)))
                        .content(d.getFormattedContent())
                        .retrievalScore(extractScore(d, i))
                        .metadata(d.getMetadata())
                        .build());
            }
            return results;
        } finally {
            sample.stop(Timer.builder("retrieval.vector.latency")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    private String metaStr(Document d, String key, String fallback) {
        Object v = d.getMetadata().get(key);
        return v == null ? fallback : v.toString();
    }

    // 真实的检索得分保存在 Spring AI 的标准元数据键中
    // （distance / score）。对于不填充这些键的向量库，
    // 回退到基于排名的衰减得分，保证流水线仍可用。
    private double extractScore(Document d, int rank) {
        Double explicit = readDouble(d.getMetadata(), "score");
        if (explicit != null) {
            return clamp01(explicit);
        }
        Double distance = readDouble(d.getMetadata(), "distance");
        if (distance != null) {
            return clamp01(1.0 - distance);
        }
        double fallback = 1.0 - (rank * 0.05);
        return Math.max(0.1, fallback);
    }

    private Double readDouble(java.util.Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object v = metadata.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }

    // 对双引号包裹的过滤表达式中的值进行转义。之前的实现
    // 只去除单引号，因此包含双引号（或反斜杠）的输入
    // 可能突破过滤条件并注入意外的谓词。
    private String escapeFilter(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
