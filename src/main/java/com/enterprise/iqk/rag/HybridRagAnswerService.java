package com.enterprise.iqk.rag;

import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.constants.SystemConstants;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.memory.MemoryItemRecord;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.memory.RagFactMemoryRecorder;
import com.enterprise.iqk.retrieval.CitationItem;
import com.enterprise.iqk.retrieval.CitationService;
import com.enterprise.iqk.retrieval.EvidenceItem;
import com.enterprise.iqk.retrieval.EvidenceJudgeService;
import com.enterprise.iqk.retrieval.HybridRetrievalService;
import com.enterprise.iqk.retrieval.HybridWeights;
import com.enterprise.iqk.retrieval.ScoredDocument;
import com.enterprise.iqk.retrieval.VectorRetriever;
import com.enterprise.iqk.security.TenantContext;
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
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * 完整版混合 RAG 管线，主聊天 rag_search（BuiltinToolRuntime）与评测链路（EvaluationService）共用。
 * 步骤：1 混合检索 → 1.5 无关线兜底（低于 rag.fallback-score-floor 的原始分整批作废时，
 * 向量路放宽阈值重试一次；仍无过线文档则不调模型直接返回固定话术）
 * → 2 证据判分 → 2.5 高置信事实沉淀（置信度门槛由 recorder 控制）
 * → 3 引用构建 → 4 判分结果消费（按综合分降序组织上下文、剔除低分、截断 rerankTopK）
 * → 4.5 记忆召回注入（失败降级为无记忆，不中断管线）→ 5 生成 → 6 引用脚注。
 * user prompt 为三段式：用户问题 / 检索上下文 / "已知记忆" 段；memoryUsed 上报实际注入的记忆标签。
 * 已知瑕疵：token 估算未把 memorySection 计入，记账略低估。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRagAnswerService {

    private final HybridRetrievalService hybridRetrievalService;
    private final VectorRetriever vectorRetriever;
    private final EvidenceJudgeService evidenceJudgeService;
    private final CitationService citationService;
    private final ChatClient chatClient;
    private final ModelRouter modelRouter;
    private final RagProperties ragProperties;
    private final MeterRegistry meterRegistry;
    private final TenantCostService tenantCostService;
    private final RagFactMemoryRecorder ragFactMemoryRecorder;
    private final MemoryService memoryService;

    /** 执行完整混合 RAG 管线：检索为空返回固定话术；每轮生成 traceId 供日志关联，全链路埋点。 */
    public HybridRagResult answer(String prompt, String tenantId, String chatId,
                                   String conversationId, String modelProfile) {
        Timer.Sample pipelineSample = Timer.start(meterRegistry);
        String traceId = "trace-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String pipelineOutcome = "error";

        try {
            String normalizedTenantId = TenantContext.normalize(tenantId);

            // 第 1 步：混合检索（向量 + 关键词 + 图谱 + 网络）
            HybridRetrievalService.HybridRetrievalResult retrievalResult =
                    hybridRetrievalService.retrieve(prompt, normalizedTenantId, chatId,
                            ragProperties.getRetrieveTopK());

            List<ScoredDocument> retrievedDocs = retrievalResult.documents();
            // 当次实际生效的归一化召回路权重，随结果回传（前端轨迹色条按此绘制，空结果也带）
            Map<String, Double> laneWeights = laneWeights(retrievalResult.weights());
            if (retrievedDocs.isEmpty()) {
                pipelineOutcome = "empty";
                return HybridRagResult.builder()
                        .answer(EMPTY_ANSWER)
                        .citations(List.of())
                        .evidence(List.of())
                        .traceId(traceId)
                        .memoryUsed(List.of())
                        .weights(laneWeights)
                        .build();
            }

            // 第 1.5 步：无关线兜底——原始检索分低于线的文档不进判分、事实沉淀与生成；
            // 整批低于线时走向量放宽重试，仍无过线文档则不调模型直接返回固定话术
            List<ScoredDocument> usableDocs = filterUsable(retrievedDocs);
            if (usableDocs.isEmpty()) {
                usableDocs = relaxedVectorRetry(prompt, normalizedTenantId, chatId, retrievalResult.weights());
            }
            if (usableDocs.isEmpty()) {
                pipelineOutcome = "empty";
                log.warn("混合检索全部命中低于无关线（floor={}），不调模型直接返回空话术: chatId={}, query={}",
                        ragProperties.getFallbackScoreFloor(), chatId, prompt);
                return HybridRagResult.builder()
                        .answer(EMPTY_ANSWER)
                        .citations(List.of())
                        // 提示行借 EvidenceItem.snippet 装载（映射观测载荷时以 snippet 文本呈现，
                        // 与主聊天旧单路的空结果提示行同句）
                        .evidence(List.of(EvidenceItem.builder().snippet(EMPTY_EVIDENCE_HINT).build()))
                        .traceId(traceId)
                        .memoryUsed(List.of())
                        .weights(laneWeights)
                        .build();
            }

            // 第 2 步：证据评审
            List<EvidenceItem> evidence = evidenceJudgeService.judge(usableDocs, prompt);

            // Step 2.5：把高置信证据写入租户级 fact 记忆
            //（尽力而为；recorder 内部做了置信度门槛与条数上限，
            // 绝不会拖慢或中断 RAG 管线）
            ragFactMemoryRecorder.recordFacts(normalizedTenantId, evidence);

            // 第 3 步：构建引用
            List<CitationItem> citations = citationService.buildCitations(evidence);

            // 第 4 步：消费判分结果——评审不再只喂展示层：
            // 按综合分降序组织上下文、剔除低于垃圾线的证据、截断到 rerankTopK 条进入 prompt
            List<ScoredDocument> contextDocs = selectContextDocs(usableDocs, evidence);
            String context = buildContext(contextDocs);

            // Step 4.5: 召回记忆（用户画像 / 近期会话要点 / 高置信事实）
            // 注入生成上下文。尽力而为：召回失败只记日志，绝不中断 RAG 管线。
            MemoryService.MemoryContextSnapshot memorySnapshot =
                    recallMemory(normalizedTenantId, chatId);
            String memorySection = memorySnapshot != null
                    && StringUtils.hasText(memorySnapshot.contextText())
                    ? "\n\n已知记忆:\n" + memorySnapshot.contextText().trim() : "";

            // 第 5 步：通过 LLM 生成回答
            ModelRouter.ModelRouteDecision decision = modelRouter.resolve(
                    modelProfile, "rag_hybrid", normalizedTenantId, chatId);
            long inputTokens = tenantCostService.estimateTokens(prompt + "\n" + context);
            tenantCostService.assertBudget(normalizedTenantId, decision.costTier(), inputTokens, 600);

            String answer = chatClient.prompt()
                    .options(ChatOptions.builder().model(decision.model())
                            .temperature(ragProperties.getTemperature()).build())
                    .system(SystemConstants.HYBRID_RAG_ANSWER_SYSTEM)
                    .user("用户问题:%n%s%n%n上下文:%n%s%s%n".formatted(prompt, context, memorySection))
                .advisors(a -> a.param(CONVERSATION_ID, conversationId))
                    .call()
                    .content();

            long outputTokens = tenantCostService.estimateTokens(answer);
            tenantCostService.recordUsage(normalizedTenantId, decision.costTier(),
                    inputTokens, outputTokens, "rag_hybrid");

            // 第 6 步：追加引用来源脚注
            String answerWithCitations = answer + citationService.formatCitationFooter(citations);

            pipelineOutcome = "success";
            return HybridRagResult.builder()
                    .answer(answerWithCitations)
                    .citations(citations)
                    .evidence(evidence)
                    .traceId(traceId)
                    .memoryUsed(memoryUsedLabels(memorySnapshot))
                    .weights(laneWeights)
                    .retrievalStats(HybridRagResult.RetrievalStats.builder()
                            .totalRetrieved(retrievalResult.totalBeforeDedup())
                            .afterDedup(retrievalResult.totalAfterDedup())
                            .finalCount(contextDocs.size())
                            .build())
                    .build();

        } finally {
            pipelineSample.stop(Timer.builder("rag.hybrid.pipeline.latency")
                    .description("Overall latency for hybrid RAG pipeline")
                    .tag("outcome", pipelineOutcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
            Counter.builder("rag.hybrid.pipeline.requests")
                    .description("Total hybrid RAG pipeline requests")
                    .tag("outcome", pipelineOutcome)
                    .register(meterRegistry)
                    .increment();
        }
    }

    /** 检索为空/全部无关时的统一话术（与 RagAnswerService 空路径同句）与提示行。 */
    private static final String EMPTY_ANSWER = "没有在当前知识库中检索到可用内容。";
    private static final String EMPTY_EVIDENCE_HINT = "未检索到匹配文档，请先上传资料或调整检索词。";

    /**
     * 无关线兜底：原始检索分（向量路为余弦相似度，含同会话有界加分 +0.05，
     * 与 RagAnswerService 的 dropCompletelyIrrelevant 相比门槛略松但方向保守）
     * 低于 rag.fallback-score-floor 的文档不进判分、事实沉淀与生成。
     */
    private List<ScoredDocument> filterUsable(List<ScoredDocument> docs) {
        return docs.stream().filter(this::usable).toList();
    }

    /** 单条文档是否过无关线。 */
    private boolean usable(ScoredDocument doc) {
        return doc.getRetrievalScore() >= ragProperties.getFallbackScoreFloor();
    }

    /**
     * 向量放宽重试：四路严格结果全部低于无关线时，按 accept-all 阈值把向量路再查一次
     * （与 RagAnswerService 的泛问兜底同款），捞回结果重新过线后单独使用——
     * 能触发重试即说明其他路没有任何过线文档，无需合并。
     * 判分相关度以 finalScore 为基底，重试文档不经 HybridRetrievalService 加权，在此按向量权重补齐。
     * 任何异常降级为空列表：兜底通道的故障不升级成整次回答 error。
     */
    private List<ScoredDocument> relaxedVectorRetry(String query, String tenantId, String chatId,
                                                    HybridWeights weights) {
        try {
            List<ScoredDocument> docs = vectorRetriever.retrieve(query, tenantId, chatId,
                    SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL);
            double vectorWeight = weights != null ? weights.vectorWeight() : HybridWeights.DEFAULT.vectorWeight();
            for (ScoredDocument d : docs) {
                d.setFinalScore(d.getRetrievalScore() * vectorWeight);
            }
            return docs.stream()
                    .filter(this::usable)
                    .sorted(Comparator.comparingDouble(ScoredDocument::getRetrievalScore).reversed())
                    .toList();
        } catch (Exception ex) {
            log.warn("向量放宽重试失败（按无可用内容处理）: chatId={}, query={}, reason={}",
                    chatId, query, ex.toString());
            return List.of();
        }
    }

    /** 归一化召回路权重 → 固定 vector/keyword/graph/web 顺序的有序 Map（前端按插入序绘制色条）。 */
    private Map<String, Double> laneWeights(HybridWeights weights) {
        if (weights == null) {
            return Map.of();
        }
        Map<String, Double> ordered = new LinkedHashMap<>();
        ordered.put("vector", weights.vectorWeight());
        ordered.put("keyword", weights.keywordWeight());
        ordered.put("graph", weights.graphWeight());
        ordered.put("web", weights.webWeight());
        return ordered;
    }

    /** 记忆召回：任何失败都返回 null，按"无记忆可用"降级。 */
    private MemoryService.MemoryContextSnapshot recallMemory(String tenantId, String chatId) {
        try {
            return memoryService.buildContext(tenantId, chatId);
        } catch (Exception ex) {
            log.warn("记忆召回失败（不影响 RAG 管线）: chatId={}, reason={}", chatId, ex.toString());
            return null;
        }
    }

    /** 把本次实际注入上下文的记忆整理为可展示标签（type + 内容摘要）。 */
    private List<String> memoryUsedLabels(MemoryService.MemoryContextSnapshot snapshot) {
        if (snapshot == null) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        snapshot.shortMemories().forEach(m -> labels.add(label("short", m)));
        snapshot.longMemories().forEach(m -> labels.add(label("long", m)));
        snapshot.facts().forEach(m -> labels.add(label("fact", m)));
        return labels;
    }

    /** 生成 "type: 内容摘要" 单条记忆标签，内容超 80 字符截断加省略号。 */
    private String label(String type, MemoryItemRecord memory) {
        String content = memory.getContent() == null ? "" : memory.getContent().replaceAll("\\s+", " ").trim();
        return type + ": " + (content.length() <= 80 ? content : content.substring(0, 80) + "…");
    }

    /** 证据垃圾线：综合分低于此值的证据不进入生成上下文（避免噪声证据稀释 prompt） */
    private static final double CONTEXT_SCORE_FLOOR = 0.30;

    /**
     * 用判分结果组织进入 prompt 的文档集（评审的消费端）：
     * evidence 已按综合分降序，取分数 ≥ 垃圾线的头部 rerankTopK 条，
     * 按 sourceType|chunkId 关联回原文档（EvidenceItem 不携带正文）。
     * 降级：判分为空退回检索序；全部低于垃圾线时退回判分序头部——
     * 评审失效不阻塞管线，宁可用次优上下文也不返回假"知识库为空"。
     */
    private List<ScoredDocument> selectContextDocs(List<ScoredDocument> retrievedDocs,
                                                   List<EvidenceItem> evidence) {
        int limit = Math.max(1, ragProperties.getRerankTopK());
        if (evidence == null || evidence.isEmpty()) {
            return retrievedDocs;
        }
        Map<String, ScoredDocument> byKey = new HashMap<>();
        for (ScoredDocument d : retrievedDocs) {
            byKey.put(contextKey(d.getSourceType(), d.getTitle(), d.getChunkId()), d);
        }
        List<ScoredDocument> selected = new ArrayList<>();
        for (EvidenceItem item : evidence) {
            if (item.getScore() < CONTEXT_SCORE_FLOOR) {
                continue;
            }
            ScoredDocument doc = byKey.get(contextKey(item.getSourceType(), item.getTitle(), item.getChunkId()));
            if (doc != null) {
                selected.add(doc);
            }
            if (selected.size() >= limit) {
                return selected;
            }
        }
        return selected.isEmpty() ? retrievedDocs.stream().limit(limit).toList() : selected;
    }

    /**
     * 证据与原文档的关联键（EvidenceItem 是对外契约结构，不加内部字段）。
     * 必须携带 title：各文档入库时切片号独立从 0 编起，仅 sourceType|chunkId
     * 会让不同文件的同号切片在 map 里互相覆盖——引用标的是 B 文档，
     * 进 prompt 的正文却被换成 A 文档，模型凭空"丢"了整份文档的内容。
     */
    private String contextKey(String sourceType, String title, String chunkId) {
        return sourceType + "|" + (title == null ? "" : title) + "|" + chunkId;
    }

    /** 把检索文档拼成 "[n] source=..., title=..., chunk=..." 编号上下文块，供 prompt 引用。 */
    private String buildContext(List<ScoredDocument> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            ScoredDocument d = docs.get(i);
            sb.append("[").append(i + 1).append("] ")
                    .append("source=").append(d.getSourceType())
                    .append(", title=").append(d.getTitle())
                    .append(", chunk=").append(d.getChunkId())
                    .append("\n")
                    .append(d.getContent())
                    .append("\n\n");
        }
        return sb.toString();
    }

    /** 混合 RAG 问答结果载体。 */
    @Data
    @Builder
    public static class HybridRagResult {
        /** 最终回答（已追加引用来源脚注） */
        private String answer;
        /** 引用列表（CitationService 构建） */
        private List<CitationItem> citations;
        /** 判分后的证据列表 */
        private List<EvidenceItem> evidence;
        /** 本次管线追踪 ID，用于日志关联 */
        private String traceId;
        /** 实际注入上下文的记忆标签（type + 内容摘要） */
        private List<String> memoryUsed;
        /** 本次检索实际生效的归一化召回路权重（vector/keyword/graph/web 固定顺序，前端轨迹条照此绘制） */
        @Builder.Default
        private Map<String, Double> weights = Map.of();
        /** 检索去重前后的数量统计 */
        private RetrievalStats retrievalStats;

        /** 检索各阶段数量统计 */
        @Data
        @Builder
        public static class RetrievalStats {
            /** 去重前召回总数 */
            private int totalRetrieved;
            /** 去重后数量 */
            private int afterDedup;
            /** 最终进入上下文的数量 */
            private int finalCount;
        }
    }
}
