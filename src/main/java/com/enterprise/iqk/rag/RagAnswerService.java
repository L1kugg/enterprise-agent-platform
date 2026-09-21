package com.enterprise.iqk.rag;

import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.constants.SystemConstants;
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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * 简单版 RAG 问答链路：向量检索（租户过滤，会话软作用域）→ 本地词面重排 → 引用化生成。
 * 既是用户可见 /ai/pdf/chat 的后端，也被 BuiltinToolRuntime 的 rag_search 动作复用；
 * 检索为空时不调 LLM 直接返回固定话术；全链路埋点 rag.pipeline/retrieval/rerank 指标。
 * chat_id 不做硬过滤（否则知识库按会话割裂），同会话命中文档由 ChatScope 有界加分。
 */
@Service
@RequiredArgsConstructor
public class RagAnswerService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final ModelRouter modelRouter;
    private final RagProperties ragProperties;
    private final MeterRegistry meterRegistry;
    private final TenantCostService tenantCostService;

    /** 执行完整问答：检索为空返回固定话术，否则重排选优、预算断言后生成并追加引用脚注。 */
    public RagResult answer(String prompt, String tenantId, String chatId, String conversationId, String modelProfile) {
        Timer.Sample pipelineSample = Timer.start(meterRegistry);
        String pipelineOutcome = "error";

        try {
            String normalizedTenantId = TenantContext.normalize(tenantId);
            String filterExpression = "tenant_id == \"" + escapeFilterValue(normalizedTenantId) + "\"";
            SearchRequest request = SearchRequest.builder()
                    .query(prompt)
                    .topK(ragProperties.getRetrieveTopK())
                    .similarityThreshold(ragProperties.getSimilarityThreshold())
                    .filterExpression(filterExpression)
                    .build();

            List<Document> retrieved = similaritySearchWithMetrics(request);
            if (retrieved == null || retrieved.isEmpty()) {
                pipelineOutcome = "empty";
                return RagResult.builder()
                        .answer("没有在当前知识库中检索到可用内容。")
                        .citations(List.of())
                        .evidence(List.of("未检索到匹配文档，请先上传资料或调整检索词。"))
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
            String answer = chatClient.prompt()
                    .options(ChatOptions.builder().model(decision.model())
                            .temperature(ragProperties.getTemperature()).build())
                    .system(SystemConstants.RAG_ANSWER_SYSTEM)
                    .user("用户问题:%n%s%n%n上下文:%n%s%n".formatted(prompt, context))
                .advisors(a -> a.param(CONVERSATION_ID, conversationId)
                        .param(MemoryInjectionAdvisor.MEMORY_TENANT_KEY, normalizedTenantId)
                        .param(MemoryInjectionAdvisor.MEMORY_USER_KEY, memoryUserKey))
                    .call()
                    .content();
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
    }
}
