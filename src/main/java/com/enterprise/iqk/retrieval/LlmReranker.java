package com.enterprise.iqk.retrieval;

import com.enterprise.iqk.llm.ModelCallGuard;
import com.enterprise.iqk.llm.ModelRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 相关性重排器（rag.reranker=llm，默认策略）：把检索候选（截断文本）交给
 * economy 档模型逐条打 0-10 相关性分，按分重排并更新 finalScore（判分相关度基底）。
 * 弹性：ModelCallGuard 熔断/超时；LLM 不可用、输出不可解析、缺分文档一律回退原序——
 * 重排是质量增强而不是正确性依赖，任何失败都不能让检索挂掉。
 * 打分只重排序与 finalScore，不改动 retrievalScore（无关线兜底仍按原始检索分判定）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.reranker", havingValue = "llm", matchIfMissing = true)
public class LlmReranker implements Reranker {

    /** 候选送审上限：超出部分不参与重排，直接排在末尾保留。 */
    private static final int MAX_CANDIDATES = 12;
    /** 单候选正文截断长度（重排只需片段级语义，省 token）。 */
    private static final int SNIPPET_CHARS = 400;

    private static final String SYSTEM_PROMPT =
            "你是检索结果重排器。给定用户查询与若干候选片段,为每个片段输出与查询的相关性分数(0-10,整数)。"
                    + "只输出 JSON 数组,格式:[{\"i\":候选序号,\"s\":分数}],不要输出任何其他文字。";

    private final ChatClient agentChatClient;
    private final ModelRouter modelRouter;
    private final ModelCallGuard modelCallGuard;
    private final ObjectMapper objectMapper;

    public LlmReranker(ChatClient agentChatClient, ModelRouter modelRouter,
                       ModelCallGuard modelCallGuard, ObjectMapper objectMapper) {
        this.agentChatClient = agentChatClient;
        this.modelRouter = modelRouter;
        this.modelCallGuard = modelCallGuard;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "llm";
    }

    @Override
    public List<ScoredDocument> rerank(String tenantId, String chatId, String query,
                                       List<ScoredDocument> documents, int topK) {
        if (documents == null || documents.isEmpty() || !StringUtils.hasText(query)) {
            return documents == null ? List.of() : documents.stream().limit(topK).toList();
        }
        List<ScoredDocument> candidates = documents.stream().limit(MAX_CANDIDATES).toList();
        try {
            ModelRouter.ModelRouteDecision decision =
                    modelRouter.resolve("economy", "rerank", tenantId, chatId);
            String userPrompt = buildUserPrompt(query, candidates);
            String raw = modelCallGuard.call("rerank", () -> agentChatClient.prompt()
                    .options(ChatOptions.builder().model(decision.model()).temperature(0.0).build())
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content());
            List<ScoredDocument> ranked = applyScores(candidates, raw);
            List<ScoredDocument> result = new ArrayList<>(ranked);
            // 未进重排池的尾部文档原序保留在结果末尾
            for (int i = MAX_CANDIDATES; i < documents.size() && result.size() < documents.size(); i++) {
                result.add(documents.get(i));
            }
            return result.stream().limit(topK).toList();
        } catch (Exception ex) {
            // 重排失败回退原序:质量增强不可用时不阻断检索
            log.warn("LLM 重排失败，回退原序: query={}, reason={}", query, ex.toString());
            return documents.stream().limit(topK).toList();
        }
    }

    private String buildUserPrompt(String query, List<ScoredDocument> candidates) {
        StringBuilder sb = new StringBuilder("用户查询:").append(query).append('\n');
        for (int i = 0; i < candidates.size(); i++) {
            ScoredDocument doc = candidates.get(i);
            String text = doc.getContent() == null ? "" : doc.getContent();
            if (text.length() > SNIPPET_CHARS) {
                text = text.substring(0, SNIPPET_CHARS);
            }
            sb.append("[")
                    .append(i)
                    .append("] ")
                    .append(text.replace('\n', ' '))
                    .append('\n');
        }
        return sb.toString();
    }

    /** 解析 LLM 的 JSON 评分数组，按分降序重排并更新 finalScore；缺分文档保持原相对顺序排后。 */
    private List<ScoredDocument> applyScores(List<ScoredDocument> candidates, String raw)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode scores = objectMapper.readTree(extractJsonArray(raw));
        Map<Integer, Double> scoreById = new LinkedHashMap<>();
        for (JsonNode item : scores) {
            if (item.has("i") && item.has("s") && item.get("i").isInt()) {
                scoreById.put(item.get("i").asInt(), Math.max(0.0, Math.min(10.0, item.get("s").asDouble())));
            }
        }
        List<ScoredDocument> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator
                .comparingDouble((ScoredDocument d) -> scoreById.getOrDefault(candidates.indexOf(d), -1.0))
                .reversed());
        for (ScoredDocument doc : ranked) {
            int index = candidates.indexOf(doc);
            Double score = scoreById.get(index);
            if (score != null) {
                doc.setFinalScore(score / 10.0);
            }
        }
        return ranked;
    }

    /** 从模型输出中提取首个 JSON 数组（容忍 ```json 围栏与前后噪声）。 */
    private String extractJsonArray(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("空输出");
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("输出中无 JSON 数组");
        }
        return raw.substring(start, end + 1);
    }
}
