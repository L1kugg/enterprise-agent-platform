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

/**
 * 向量语义检索器：基于 Spring AI VectorStore 相似度搜索，按租户过滤表达式圈定范围，
 * topK 与相似度阈值取自 RagProperties。
 * 作用域设计：chat_id 不做硬过滤（否则知识库按会话割裂，临时 chatId 的调用方
 * 如 DeepResearch 必然空结果），改为同会话命中的文档获得 {@link ChatScope#BOOST} 有界加分。
 * 设计要点：得分优先读向量库元数据（score/distance），不填充这些键的向量库
 * 回退到按排名衰减的得分，保证下游加权融合始终有分数可用。
 */
@Component
@RequiredArgsConstructor
public class VectorRetriever {

    private final VectorStore vectorStore;
    private final RagProperties ragProperties;
    private final MeterRegistry meterRegistry;

    /** 按配置阈值（rag.similarity-threshold）检索；四路混合检索的常规入口。 */
    public List<ScoredDocument> retrieve(String query, String tenantId, String chatId) {
        return retrieve(query, tenantId, chatId, ragProperties.getSimilarityThreshold());
    }

    /**
     * 向量相似度检索并映射为 ScoredDocument（docId 形如 vec-0，sourceType=vector），
     * 相似度阈值由调用方显式给定——HybridRagAnswerService 的无关线兜底用
     * SIMILARITY_THRESHOLD_ACCEPT_ALL 放宽重试就走这个重载。
     * 记录延迟指标；异常不在此捕获、直接上抛，由 HybridRetrievalService 统一降级为空结果。
     */
    public List<ScoredDocument> retrieve(String query, String tenantId, String chatId, double similarityThreshold) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            String filter = filterExpression(tenantId);
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(ragProperties.getRetrieveTopK())
                    .similarityThreshold(similarityThreshold)
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
                        .rawText(d.getText())
                        .retrievalScore(ChatScope.boost(extractScore(d, i), d, chatId))
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

    /**
     * 租户级过滤表达式（包私有以便测试断言作用域语义）：不含 chat_id——
     * 知识库按租户共享，会话相关性由 {@link ChatScope} 的有界加分表达。
     */
    String filterExpression(String tenantId) {
        return "tenant_id == \"" + escapeFilter(tenantId) + "\"";
    }

    /** 读取文档元数据并转字符串，缺失时返回 fallback */
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

    /** 从元数据读取数值（接受 Number 或可解析的字符串），取不到返回 null */
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

    /** 夹紧到 [0,1]，NaN/无穷按 0 处理 */
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
