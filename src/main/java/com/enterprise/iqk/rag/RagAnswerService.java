package com.enterprise.iqk.rag;

import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.constants.SystemConstants;
import com.enterprise.iqk.llm.ModelCallGuard;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.memory.MemoryInjectionAdvisor;
import com.enterprise.iqk.retrieval.ChatScope;
import com.enterprise.iqk.retrieval.LexicalMatcher;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.security.UserContext;
import com.enterprise.iqk.service.TenantCostService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * 简单版 RAG 问答链路：向量检索（租户过滤，会话软作用域，空结果放宽阈值重试一次，
 * 兜底捞回按绝对相似度剔除完全无关结果）
 * → 本地词面重排 → 引用化生成。
 * 既是用户可见 /ai/pdf/chat 的后端，也被 BuiltinToolRuntime 的 rag_search 动作复用；
 * 兜底重试仍为空时不调 LLM 直接返回固定话术；全链路埋点 rag.pipeline/retrieval/rerank 指标。
 * chat_id 不做硬过滤（否则知识库按会话割裂），同会话命中文档由 ChatScope 有界加分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagAnswerService {

    private final VectorStore vectorStore;
    private final ModelCallGuard modelCallGuard;
    private final ChatClient chatClient;
    private final ModelRouter modelRouter;
    private final RagProperties ragProperties;
    private final MeterRegistry meterRegistry;
    private final TenantCostService tenantCostService;

    /** 本链路为向量单路检索：权重固定 {vector:1.0}，随结果回传供前端轨迹条如实绘制 */
    private static final Map<String, Double> VECTOR_ONLY_WEIGHTS = Map.of("vector", 1.0);

    /** 执行完整问答：检索为空返回固定话术，否则重排选优、预算断言后生成并追加引用脚注。 */
    public RagResult answer(String prompt, String tenantId, String chatId, String conversationId, String modelProfile) {
        return answer(prompt, tenantId, chatId, conversationId, modelProfile, false);
    }

    /**
     * 执行完整问答：检索为空返回固定话术，否则重排选优、预算断言后生成并追加引用脚注。
     * docScoped=true 时在租户范围内追加 chat_id 硬过滤，只在指定文档的切片里检索
     * （"只根据这份文档回答"模式）；false 为租户全库共享检索。
     */
    public RagResult answer(String prompt, String tenantId, String chatId, String conversationId,
                            String modelProfile, boolean docScoped) {
        Timer.Sample pipelineSample = Timer.start(meterRegistry);
        String pipelineOutcome = "error";

        try {
            String normalizedTenantId = TenantContext.normalize(tenantId);
            String filterExpression = tenantFilter(normalizedTenantId, chatId, docScoped);

            List<Document> retrieved = retrieveWithRelaxedFallback(prompt, filterExpression);
            if (retrieved == null || retrieved.isEmpty()) {
                pipelineOutcome = "empty";
                return RagResult.builder()
                        .answer("没有在当前知识库中检索到可用内容。")
                        .citations(List.of())
                        .evidence(List.of("未检索到匹配文档，请先上传资料或调整检索词。"))
                        .weights(VECTOR_ONLY_WEIGHTS)
                        .build();
            }

            List<Document> reranked = rerankWithMetrics(prompt, retrieved, chatId);
            List<Document> selected = reranked.stream()
                    .limit(Math.max(1, ragProperties.getRerankTopK()))
                    .toList();

            String context = buildContext(selected);
            ModelRouter.ModelRouteDecision decision = modelRouter.resolve(modelProfile, "rag", normalizedTenantId, chatId);
            long inputTokens = tenantCostService.estimateTokens(prompt + "\n" + context);
            tenantCostService.assertBudget(normalizedTenantId, decision.costTier(), inputTokens, 600);
            // 记忆注入：认证主体为 user 键（匿名回落 chatId），advisor 组装期插
            // "已知记忆" system 消息（long/fact 跨会话视图，short 留给 ChatMemory）。
            String memoryUserKey = UserContext.currentUserId(chatId);
            // 生成步经熔断/重试/超时守卫：LLM 持续不可用时快速失败并返回固定兜底文案
            String answer;
            try {
                answer = modelCallGuard.call("rag", () -> chatClient.prompt()
                        .options(ChatOptions.builder().model(decision.model())
                                .temperature(ragProperties.getTemperature()).build())
                        .system(SystemConstants.RAG_ANSWER_SYSTEM)
                        .user("用户问题:%n%s%n%n上下文:%n%s%n".formatted(prompt, context))
                    .advisors(a -> a.param(CONVERSATION_ID, conversationId)
                            .param(MemoryInjectionAdvisor.MEMORY_TENANT_KEY, normalizedTenantId)
                            .param(MemoryInjectionAdvisor.MEMORY_USER_KEY, memoryUserKey))
                        .call()
                        .content());
            } catch (RuntimeException ex) {
                pipelineOutcome = "generation_fallback";
                log.warn("RAG 生成失败，返回固定兜底文案: chatId={}, reason={}", chatId, ex.toString());
                return RagResult.builder()
                        .answer("模型服务暂时不可用，请稍后重试。")
                        .citations(List.of())
                        .evidence(List.of())
                        .weights(VECTOR_ONLY_WEIGHTS)
                        .build();
            }
            long outputTokens = tenantCostService.estimateTokens(answer);
            tenantCostService.recordUsage(normalizedTenantId, decision.costTier(), inputTokens, outputTokens, "rag");

            List<String> citations = selected.stream()
                    .map(this::citationText)
                    .toList();
            List<String> evidence = selected.stream()
                    .map(this::evidenceText)
                    .toList();
            String answerWithFooter = answer + formatCitationFooter(citations);

            pipelineOutcome = "success";
            return RagResult.builder()
                    .answer(answerWithFooter)
                    .citations(citations)
                    .evidence(evidence)
                    .weights(VECTOR_ONLY_WEIGHTS)
                    .build();
        } finally {
            pipelineSample.stop(Timer.builder("rag.pipeline.latency")
                    .description("Overall latency for RAG answer pipeline")
                    .tag("outcome", pipelineOutcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
            Counter.builder("rag.pipeline.requests")
                    .description("Total number of RAG pipeline requests")
                    .tag("outcome", pipelineOutcome)
                    .register(meterRegistry)
                    .increment();
        }
    }

    /**
     * 相似度检索 + 空结果兜底：先按配置阈值检索；为空则放宽阈值按最近邻重试一次，
     * 兜底捞回的结果再按绝对相似度剔除完全无关的（见 {@link #dropCompletelyIrrelevant}）。
     * 泛问（如"这份文档讲了什么"）与具体切片的向量相似度天然偏低，严格阈值下会被
     * 全部刷掉导致"明明有文档却检索为空"；放宽后捞回的仍是过滤范围内的最相似切片。
     */
    List<Document> retrieveWithRelaxedFallback(String prompt, String filterExpression) {
        List<Document> retrieved = similaritySearchWithMetrics(SearchRequest.builder()
                .query(prompt)
                .topK(ragProperties.getRetrieveTopK())
                .similarityThreshold(ragProperties.getSimilarityThreshold())
                .filterExpression(filterExpression)
                .build());
        if (retrieved != null && !retrieved.isEmpty()) {
            return retrieved;
        }
        List<Document> relaxed = similaritySearchWithMetrics(SearchRequest.builder()
                .query(prompt)
                .topK(ragProperties.getRetrieveTopK())
                .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
                .filterExpression(filterExpression)
                .build());
        return dropCompletelyIrrelevant(relaxed);
    }

    /**
     * 兜底捞回结果的"无关线"：放宽阈值是给泛问留的通道，但完全无关的问题（问美食、
     * 库里是 Java 面试题）在阈值机制下与泛问长得一样——最近邻照样被捞回，引用脚注
     * 还照拼，用户看到"答非所问还带引用"。这里按绝对相似度再卡一条线（低于
     * fallbackScoreFloor 视为完全无关），全部低于线即整批作废，走"没检索到"话术；
     * 分数读不出来时保守保留（兜底通道不因元数据缺失而失效）。
     */
    private List<Document> dropCompletelyIrrelevant(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return docs;
        }
        return docs.stream()
                .filter(d -> {
                    Double score = extractSimilarity(d);
                    return score == null || score >= ragProperties.getFallbackScoreFloor();
                })
                .toList();
    }

    /** 与 VectorRetriever 同口径读相似度：优先 score 元数据，退回 1-distance；读不出返回 null。 */
    private Double extractSimilarity(Document d) {
        Double score = readScoreValue(d.getMetadata(), "score");
        if (score != null) {
            return score;
        }
        Double distance = readScoreValue(d.getMetadata(), "distance");
        return distance == null ? null : 1.0 - distance;
    }

    /** 从元数据读数值（接受 Number 或可解析的字符串），读不出返回 null。 */
    private Double readScoreValue(java.util.Map<String, Object> metadata, String key) {
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

    /** 带指标包装的向量相似度检索，结果可能为空，由调用方判断走空话术分支。 */
    private List<Document> similaritySearchWithMetrics(SearchRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            List<Document> docs = vectorStore.similaritySearch(request);
            outcome = (docs == null || docs.isEmpty()) ? "empty" : "success";
            return docs;
        } finally {
            sample.stop(Timer.builder("rag.retrieval.latency")
                    .description("Latency of vector similarity search")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
            Counter.builder("rag.retrieval.requests")
                    .description("Number of vector retrieval requests")
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
        }
    }

    /** 带指标包装的本地重排阶段，只计耗时不计请求数。 */
    private List<Document> rerankWithMetrics(String prompt, List<Document> docs, String chatId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            List<Document> reranked = rerank(prompt, docs, chatId);
            outcome = reranked.isEmpty() ? "empty" : "success";
            return reranked;
        } finally {
            sample.stop(Timer.builder("rag.rerank.latency")
                    .description("Latency of local rerank stage")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    /** 按查询词召回分降序排序（纯本地重排，不依赖外部服务；同会话命中有界加分）。 */
    List<Document> rerank(String prompt, List<Document> docs, String chatId) {
        Set<String> promptTokens = LexicalMatcher.tokenize(prompt);
        return docs.stream()
                .sorted((a, b) -> Double.compare(scoreDoc(promptTokens, b, chatId),
                        scoreDoc(promptTokens, a, chatId)))
                .collect(Collectors.toList());
    }

    /** 重排得分 = 查询词被文档命中的比例（分母 = 查询 token 数）+ 同会话有界加分；查询无词时得 0 分。 */
    private double scoreDoc(Set<String> promptTokens, Document doc, String chatId) {
        // 用纯正文匹配——getFormattedContent 会拼上元数据前缀（file_name/chat_id 等），
        // 元数据词混入词面匹配会造成"查 pdf 命中所有文档"这类噪声
        double recall = LexicalMatcher.recallScore(promptTokens,
                LexicalMatcher.tokenize(doc.getText()));
        return ChatScope.boost(recall, doc, chatId);
    }

    /** 把选中文档拼成 "[n] source=..., chunk=..." 编号上下文块，供 prompt 引用。 */
    private String buildContext(List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            sb.append("[").append(i + 1).append("] ")
                    .append(citationText(d))
                    .append("\n")
                    .append(d.getFormattedContent())
                    .append("\n\n");
        }
        return sb.toString();
    }

    /** 从文档元数据取 file_name/chunk_index 生成单条引用文本。 */
    private String citationText(Document d) {
        Object file = d.getMetadata().getOrDefault("file_name", "unknown");
        Object chunk = d.getMetadata().getOrDefault("chunk_index", "?");
        return "source=" + file + ", chunk=" + chunk;
    }

    /** 压缩空白并截断到 180 字符生成证据摘要。 */
    private String evidenceText(Document d) {
        String raw = emptyIfBlank(d.getFormattedContent()).replaceAll("\\s+", " ").trim();
        if (raw.length() <= 180) {
            return raw;
        }
        return raw.substring(0, 180) + "...";
    }

    /** null/空白统一返回空串。 */
    private String emptyIfBlank(String value) {
        return StringUtils.hasText(value) ? value : "";
    }

    /** 转义反斜杠与双引号，防止 chatId 等拼入过滤表达式造成注入。 */
    private String escapeFilterValue(String value) {
        String raw = emptyIfBlank(value);
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 租户过滤表达式；docScoped 时追加 chat_id 硬过滤，圈定单文档检索范围。 */
    private String tenantFilter(String tenantId, String chatId, boolean docScoped) {
        String expression = "tenant_id == \"" + escapeFilterValue(tenantId) + "\"";
        return docScoped
                ? expression + " && chat_id == \"" + escapeFilterValue(chatId) + "\""
                : expression;
    }

    /** 生成 "引用来源:" 编号脚注；无引用时返回空串。 */
    private String formatCitationFooter(List<String> citations) {
        if (citations == null || citations.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n---\n引用来源:\n");
        for (int i = 0; i < citations.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(citations.get(i)).append("\n");
        }
        return sb.toString();
    }

    /** RAG 问答结果载体。 */
    @Data
    @Builder
    public static class RagResult {
        /** 最终回答（已追加引用来源脚注） */
        private String answer;
        /** 引用列表，格式 source=..., chunk=... */
        private List<String> citations;
        /** 证据摘要列表（正文压缩截断，与选中文档一一对应） */
        private List<String> evidence;
        /** 本次检索实际生效的召回路权重（本链路为向量单路：{vector:1.0}） */
        private Map<String, Double> weights;
    }
}
