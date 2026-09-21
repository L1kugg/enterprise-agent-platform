package com.enterprise.iqk.retrieval;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 关键词检索器：在向量库候选池上做词面精确匹配重排，与 VectorRetriever 的语义排序互补，
 * 捕获语义搜索可能遗漏的精确词命中（术语、错误码、编号等）。
 * 三个修复点：
 * 1. 候选池按租户圈定、相似度阈值放开为 0（ACCEPT_ALL）、池扩到 max(topK×4, 40)——
 *    此前候选与向量路同查询同阈值 0.25，只是把语义序重新洗牌；阈值放开后
 *    嵌入距离远但词面精确命中的文档也能进入候选。诚实边界：候选仍来自向量库
 *    （无独立倒排索引），本路是"向量候选池上的词法重排"，独立 BM25/全文索引是演进方向。
 * 2. 打分用查询召回分（分母 = 查询 token 数，见 {@link LexicalMatcher#recallScore}）——
 *    此前除以文档 token 数，长文档分数必然趋零。
 * 3. 切词 CJK 感知（中文连续段切 2-gram，见 {@link LexicalMatcher#tokenize}）——
 *    此前中文整句成单 token，中文查询必然空结果。
 */
@Component
@RequiredArgsConstructor
public class KeywordRetriever {

    private final VectorStore vectorStore;
    private final MeterRegistry meterRegistry;

    /**
     * 关键词检索：借向量库按租户拉取候选，再按查询词与标题/内容的召回分重打分
     * （标题 0.6 + 内容 0.4），同会话命中的文档获得 {@link ChatScope} 有界加分，
     * 仅保留得分 &gt; 0.05 的条目并截断到 topK；异常上抛由混合检索层降级。
     */
    public List<ScoredDocument> retrieve(String query, String tenantId, String chatId, int topK) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            // 候选池：租户级 + 相似度阈值放开，让词面精确命中但语义距离远的文档也能入选
            String filter = filterExpression(tenantId);
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(Math.max(topK * 4, 40))
                            .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
                            .filterExpression(filter)
                            .build());

            Set<String> queryTokens = LexicalMatcher.tokenize(query);
            if (queryTokens.isEmpty()) {
                outcome = "empty";
                return List.of();
            }

            List<ScoredDocument> results = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) {
                Document d = docs.get(i);
                Set<String> titleTokens = LexicalMatcher.tokenize(metaStr(d, "file_name", ""));
                // 用纯正文匹配——getFormattedContent 拼有元数据前缀，元数据词混入会放大噪声
                Set<String> contentTokens = LexicalMatcher.tokenize(d.getText());

                double titleOverlap = LexicalMatcher.recallScore(queryTokens, titleTokens);
                double contentOverlap = LexicalMatcher.recallScore(queryTokens, contentTokens);
                double rawScore = titleOverlap * 0.6 + contentOverlap * 0.4;
                double score = ChatScope.boost(rawScore, d, chatId);

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

    /**
     * 租户级过滤表达式（包私有以便测试断言作用域语义）：不含 chat_id——
     * 知识库按租户共享，会话相关性由 {@link ChatScope} 的有界加分表达。
     */
    String filterExpression(String tenantId) {
        return "tenant_id == \"" + escapeFilter(tenantId) + "\"";
    }

    /** 读取文档元数据并转字符串，缺失时返回 fallback */
    private String metaStr(Document d, String key, String fallback) {
        Object v = d.getMetadata() == null ? null : d.getMetadata().get(key);
        return v == null ? fallback : v.toString();
    }

    /** 转义过滤表达式值中的反斜杠与双引号，防止突破过滤条件注入额外谓词 */
    private String escapeFilter(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
