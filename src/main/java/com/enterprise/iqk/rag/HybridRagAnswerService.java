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
import com.enterprise.iqk.retrieval.ScoredDocument;
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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * 完整版混合 RAG 管线，评测链路专用（唯一调用方 EvaluationService）。
 * 步骤：1 混合检索 → 2 证据判分 → 2.5 高置信事实沉淀（置信度门槛由 recorder 控制）
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
            if (retrievedDocs.isEmpty()) {
                pipelineOutcome = "empty";
                return HybridRagResult.builder()
                        .answer("没有在当前知识库中检索到可用内容。")
                        .citations(List.of())
                        .evidence(List.of())
                        .traceId(traceId)
                        .memoryUsed(List.of())
                        .build();
            }

            // 第 2 步：证据评审
            List<EvidenceItem> evidence = evidenceJudgeService.judge(retrievedDocs, prompt);

            // Step 2.5：把高置信证据写入租户级 fact 记忆
            //（尽力而为；recorder 内部做了置信度门槛与条数上限，
            // 绝不会拖慢或中断 RAG 管线）
            ragFactMemoryRecorder.recordFacts(normalizedTenantId, evidence);

            // 第 3 步：构建引用
            List<CitationItem> citations = citationService.buildCitations(evidence);

            // 第 4 步：消费判分结果——评审不再只喂展示层：
            // 按综合分降序组织上下文、剔除低于垃圾线的证据、截断到 rerankTopK 条进入 prompt
            List<ScoredDocument> contextDocs = selectContextDocs(retrievedDocs, evidence);
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
            byKey.put(contextKey(d.getSourceType(), d.getChunkId()), d);
        }
        List<ScoredDocument> selected = new ArrayList<>();
        for (EvidenceItem item : evidence) {
            if (item.getScore() < CONTEXT_SCORE_FLOOR) {
                continue;
            }
            ScoredDocument doc = byKey.get(contextKey(item.getSourceType(), item.getChunkId()));
            if (doc != null) {
                selected.add(doc);
            }
            if (selected.size() >= limit) {
                return selected;
            }
        }
        return selected.isEmpty() ? retrievedDocs.stream().limit(limit).toList() : selected;
    }

    /** 证据与原文档的关联键（EvidenceItem 是对外契约结构，不加内部字段） */
    private String contextKey(String sourceType, String chunkId) {
        return sourceType + "|" + chunkId;
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
