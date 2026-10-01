package com.enterprise.iqk.graph;

import com.enterprise.iqk.config.properties.GraphProperties;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.service.TenantCostService;
import com.enterprise.iqk.util.ConversationIdHelper;
import com.enterprise.iqk.util.SqlLikeUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * 知识图谱写入侧：文档入库成功后用 LLM 从切片文本提取实体/关系/事实，写入 kg 三表。
 *
 * 设计要点：
 * 1. 异步 fire-and-forget —— 图谱是检索增强数据，抽取失败绝不影响入库主链路；
 * 2. 成本受控 —— 走 economy 档模型，调用计入租户成本（预算不足时跳过不拦截用户）；
 * 3. 来源作用域 —— 约定 entity.source_id / relation.evidence_id / fact.source 存 chatId，
 *    删除文档与重建图谱都按这个作用域清理；写前删保证重复回填不翻倍；
 * 4. 实体名硬约束 —— 提示词强制 name ≤10 个汉字：读侧按 name LIKE 匹配，
 *    实体名太长在中文查询下永远命中不了；
 * 5. 尽力而为 —— 不开事务，单条插入失败记日志跳过，宁可少数据不留半截错误状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphExtractionService {

    /** 抽取结果 JSON 的系统提示词，要求模型只输出严格 JSON。 */
    private static final String SYSTEM_PROMPT = """
            你是知识图谱抽取器。从给定的文档片段中提取实体、关系和事实三元组。
            只输出 JSON，不要输出任何其他内容：
            {"entities":[{"name":"实体名","type":"CONCEPT","description":"一句话描述","aliases":["别名"]}],
             "relations":[{"source":"实体名","target":"实体名","relationType":"RELATED_TO","weight":0.8}],
             "facts":[{"subject":"主语","predicate":"谓语","object":"宾语","confidence":0.8}]}
            规则：
            - 实体 name 不超过 10 个汉字，长短语放进 description（name 太长检索时匹配不上）；
            - type 只能从 CONCEPT/TECHNOLOGY/MATERIAL/PROCESS/PERSON/ORG/COURSE/TOPIC/OTHER 中选；
            - relations 的 source 和 target 必须是 entities 里出现过的 name；
            - facts 是"主语-谓语-宾语"三元组，confidence 取 0 到 1 之间；
            - 只抽取文本里明确出现的内容，禁止推测；没有可抽取的内容时输出三个空数组。
            """;

    /** 抽取调用的端点标识（模型路由与成本记账共用）。 */
    private static final String EXTRACTION_ENDPOINT = "kg_extraction";
    /** 预算闸门使用的输出 token 预估上限（抽取输出本身很短）。 */
    private static final long ESTIMATED_OUTPUT_TOKENS = 600;
    /** 字段级截断上限（均低于对应列宽：name 255/type 64/subject 255/predicate 255/object 512）。 */
    private static final int MAX_NAME_CHARS = 100;
    private static final int MAX_DESCRIPTION_CHARS = 500;
    private static final int MAX_SPO_CHARS = 200;
    private static final int MAX_OBJECT_CHARS = 400;
    private static final int MAX_TYPE_CHARS = 64;
    private static final String DEFAULT_RELATION_TYPE = "RELATED_TO";
    private static final String DEFAULT_PREDICATE = "相关";

    private final KgEntityMapper entityMapper;
    private final KgRelationMapper relationMapper;
    private final KgFactMapper factMapper;
    private final GraphProperties graphProperties;
    private final ChatClient chatClient;
    private final ModelRouter modelRouter;
    private final TenantCostService tenantCostService;
    private final ObjectMapper objectMapper;

    /** 抽取线程池：守护线程，不阻碍 JVM 退出。 */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "kg-extract");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 入库主链路只调这个方法：把抽取任务丢进线程池立即返回。
     * 开关关闭或没有切片文本时直接 no-op。
     */
    public void submitAsync(String tenantId, String chatId, String jobId, List<String> chunkTexts) {
        if (!graphProperties.isExtractionEnabled()) {
            return;
        }
        if (chunkTexts == null || chunkTexts.isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> extractAndStore(tenantId, chatId, jobId, chunkTexts));
        } catch (Exception ex) {
            // 线程池饱和或已关闭时直接放弃，图谱抽取是尽力而为
            log.warn("图谱抽取任务提交失败（不影响入库）: chatId={}, reason={}", chatId, ex.toString());
        }
    }

    /**
     * 同步抽取核心（回填端点复用）：
     * 拼 LLM 提取 -> 解析 JSON -> 写前删旧数据 -> 依次落实体/关系/事实。
     * 全程内部 try/catch 不外抛，返回带 skipReason 的结果供调用方展示。
     */
    public GraphExtractionResult extractAndStore(String tenantId, String chatId, String jobId, List<String> chunkTexts) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(chatId)) {
            return GraphExtractionResult.skipped("missing-scope");
        }
        List<String> texts = chunkTexts == null ? List.of()
                : chunkTexts.stream().filter(StringUtils::hasText).toList();
        if (texts.isEmpty()) {
            return GraphExtractionResult.skipped("empty-input");
        }
        try {
            String userPrompt = buildUserPrompt(texts);
            ModelRouter.ModelRouteDecision decision = modelRouter.resolve(
                    graphProperties.getProfile(), EXTRACTION_ENDPOINT, tenantId, chatId);
            long inputTokens = tenantCostService.estimateTokens(SYSTEM_PROMPT + userPrompt);
            try {
                tenantCostService.assertBudget(tenantId, decision.costTier(), inputTokens, ESTIMATED_OUTPUT_TOKENS);
            } catch (Exception budgetEx) {
                return GraphExtractionResult.skipped("budget");
            }

            String raw = chatClient.prompt()
                    .options(ChatOptions.builder()
                            .model(decision.model())
                            .temperature(0.0)
                            .build())
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    // 独立会话 ID：抽取轮次不进入用户可见对话历史
                    .advisors(a -> a.param(CONVERSATION_ID,
                            ConversationIdHelper.build("kg-extract", chatId)))
                    .call()
                    .content();

            tenantCostService.recordUsage(tenantId, decision.costTier(), inputTokens,
                    tenantCostService.estimateTokens(raw), EXTRACTION_ENDPOINT);

            ExtractionPayload payload = parsePayload(raw);
            if (payload == null || isEmptyPayload(payload)) {
                return GraphExtractionResult.skipped("parse");
            }

            // 写前删：同一 chatId 重建图谱时先清旧数据，重复回填不翻倍
            deleteByChat(tenantId, chatId);

            LocalDateTime now = LocalDateTime.now();
            Map<String, String> knownEntities = new HashMap<>();
            int entityCount = storeEntities(tenantId, chatId, jobId, payload, now, knownEntities);
            int relationCount = storeRelations(tenantId, chatId, payload, now, knownEntities);
            int factCount = storeFacts(tenantId, chatId, payload, now);
            log.info("kg extraction done: tenant={}, chatId={}, entities={}, relations={}, facts={}",
                    tenantId, chatId, entityCount, relationCount, factCount);
            return new GraphExtractionResult(entityCount, relationCount, factCount, null);
        } catch (Exception ex) {
            log.warn("图谱抽取失败（不影响入库）: chatId={}, reason={}", chatId, ex.toString());
            return GraphExtractionResult.skipped("error");
        }
    }

    /** 按来源作用域清理图谱数据：先删边（relation）再删事实最后删点（entity）。 */
    public int deleteByChat(String tenantId, String chatId) {
        int relations = relationMapper.deleteByEvidence(tenantId, chatId);
        int facts = factMapper.deleteBySource(tenantId, chatId);
        int entities = entityMapper.deleteBySource(tenantId, chatId);
        return relations + facts + entities;
    }

    /** 实体落库：同名（忽略大小写）跨文档复用既有实体，否则新插入；返回 known 表最终条数。 */
    private int storeEntities(String tenantId, String chatId, String jobId, ExtractionPayload payload,
                              LocalDateTime now, Map<String, String> knownEntities) {
        int count = 0;
        for (PayloadEntity entity : payload.entities()) {
            if (count >= graphProperties.getMaxEntities()) {
                break;
            }
            String name = truncate(entity.name(), MAX_NAME_CHARS);
            if (!StringUtils.hasText(name)) {
                continue;
            }
            String key = name.toLowerCase();
            String entityId = knownEntities.get(key);
            if (entityId == null) {
                entityId = findExistingEntityId(tenantId, name);
            }
            if (entityId == null) {
                entityId = insertEntity(tenantId, chatId, jobId, entity, name, now);
            }
            if (entityId != null) {
                knownEntities.put(key, entityId);
                count++;
            }
        }
        return count;
    }

    /** 跨文档同名实体复用：名称 LIKE 检索后忽略大小写精确比对，找不到返回 null。 */
    private String findExistingEntityId(String tenantId, String name) {
        try {
            List<KgEntityRecord> hits = entityMapper.searchByName(
                    tenantId, SqlLikeUtils.escapeForLike(name), 5);
            for (KgEntityRecord hit : hits) {
                if (hit.getName() != null && hit.getName().equalsIgnoreCase(name)) {
                    return hit.getEntityId();
                }
            }
        } catch (Exception ex) {
            log.warn("kg entity reuse lookup failed: name={}, reason={}", name, ex.toString());
        }
        return null;
    }

    /** 单条实体插入，失败记日志返回 null（不影响其余实体）。 */
    private String insertEntity(String tenantId, String chatId, String jobId,
                                PayloadEntity entity, String name, LocalDateTime now) {
        try {
            String entityId = "kg-ent-" + UUID.randomUUID();
            Map<String, String> metadata = new HashMap<>();
            metadata.put("jobId", StringUtils.hasText(jobId) ? jobId : "");
            metadata.put("chatId", chatId);
            entityMapper.insert(KgEntityRecord.builder()
                    .entityId(entityId)
                    .tenantId(tenantId)
                    .name(name)
                    .type(normalizeType(entity.type()))
                    .aliases(toJsonArrayOrNull(entity.aliases()))
                    .description(StringUtils.hasText(entity.description())
                            ? truncate(entity.description(), MAX_DESCRIPTION_CHARS) : null)
                    .sourceId(chatId)
                    .metadataJson(toJsonOrNull(metadata))
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            return entityId;
        } catch (Exception ex) {
            log.warn("kg entity insert failed: name={}, reason={}", name, ex.toString());
            return null;
        }
    }

    /** 关系落库：两端都必须是本次已知的实体，同向边去重；返回成功插入数。 */
    private int storeRelations(String tenantId, String chatId, ExtractionPayload payload,
                               LocalDateTime now, Map<String, String> knownEntities) {
        int count = 0;
        Set<String> seenEdges = new HashSet<>();
        for (PayloadRelation relation : payload.relations()) {
            if (count >= graphProperties.getMaxRelations()) {
                break;
            }
            String sourceId = knownEntities.get(keyOf(relation.source()));
            String targetId = knownEntities.get(keyOf(relation.target()));
            if (sourceId == null || targetId == null || sourceId.equals(targetId)) {
                continue;
            }
            if (!seenEdges.add(sourceId + "->" + targetId)) {
                continue;
            }
            // 既有同向边直接复用（v1 不分关系类型，防止扇出翻倍）
            if (!relationMapper.findDirectRelation(tenantId, sourceId, targetId).isEmpty()) {
                continue;
            }
            try {
                relationMapper.insert(KgRelationRecord.builder()
                        .relationId("kg-rel-" + UUID.randomUUID())
                        .tenantId(tenantId)
                        .sourceEntityId(sourceId)
                        .targetEntityId(targetId)
                        .relationType(StringUtils.hasText(relation.relationType())
                                ? truncate(relation.relationType().toUpperCase(), MAX_TYPE_CHARS)
                                : DEFAULT_RELATION_TYPE)
                        .evidenceId(chatId)
                        .weight(clamp(relation.weight(), 1.0))
                        .createdAt(now)
                        .build());
                count++;
            } catch (Exception ex) {
                log.warn("kg relation insert failed: {}->{}, reason={}",
                        relation.source(), relation.target(), ex.toString());
            }
        }
        return count;
    }

    /** 事实落库：运行内三元组去重，谓语缺省"相关"，置信度缺省 0.8；返回成功插入数。 */
    private int storeFacts(String tenantId, String chatId, ExtractionPayload payload, LocalDateTime now) {
        int count = 0;
        Set<String> seenFacts = new HashSet<>();
        for (PayloadFact fact : payload.facts()) {
            if (count >= graphProperties.getMaxFacts()) {
                break;
            }
            String subject = truncate(fact.subject(), MAX_SPO_CHARS);
            String object = truncate(fact.object(), MAX_OBJECT_CHARS);
            if (!StringUtils.hasText(subject) || !StringUtils.hasText(object)) {
                continue;
            }
            String predicate = StringUtils.hasText(fact.predicate())
                    ? truncate(fact.predicate(), MAX_SPO_CHARS) : DEFAULT_PREDICATE;
            if (!seenFacts.add(subject + "|" + predicate + "|" + object)) {
                continue;
            }
            try {
                factMapper.insert(KgFactRecord.builder()
                        .factId("kg-fact-" + UUID.randomUUID())
                        .tenantId(tenantId)
                        .subject(subject)
                        .predicate(predicate)
                        .object(object)
                        .confidence(clamp(fact.confidence(), 0.8))
                        .source(chatId)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
                count++;
            } catch (Exception ex) {
                log.warn("kg fact insert failed: subject={}, reason={}", subject, ex.toString());
            }
        }
        return count;
    }

    /** 拼接送 LLM 的用户消息：切片按空行拼接，超过上限截断。 */
    private String buildUserPrompt(List<String> texts) {
        String joined = String.join("\n\n", texts);
        if (joined.length() > graphProperties.getMaxInputChars()) {
            joined = joined.substring(0, graphProperties.getMaxInputChars());
        }
        return "文档片段：\n" + joined;
    }

    /** 解析模型输出。容忍 ```json 代码块包裹；任何解析失败返回 null。 */
    ExtractionPayload parsePayload(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readValue(stripCodeFence(raw), ExtractionPayload.class);
        } catch (Exception ex) {
            return null;
        }
    }

    /** 去掉模型可能包裹的 markdown 代码块围栏。 */
    private String stripCodeFence(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstLineBreak = trimmed.indexOf('\n');
            if (firstLineBreak > 0) {
                trimmed = trimmed.substring(firstLineBreak + 1);
            }
            int closingFence = trimmed.lastIndexOf("```");
            if (closingFence >= 0) {
                trimmed = trimmed.substring(0, closingFence);
            }
        }
        return trimmed.trim();
    }

    /** 三个数组全空视为无可抽取内容。 */
    private boolean isEmptyPayload(ExtractionPayload payload) {
        return isBlankList(payload.entities()) && isBlankList(payload.relations()) && isBlankList(payload.facts());
    }

    private boolean isBlankList(List<?> list) {
        return list == null || list.isEmpty();
    }

    /** 实体类型规范化：空回退 CONCEPT，统一大写、非法字符换下划线、截断到列宽。 */
    private String normalizeType(String type) {
        if (!StringUtils.hasText(type)) {
            return "CONCEPT";
        }
        String normalized = type.trim().toUpperCase().replaceAll("[^A-Z0-9]", "_");
        return StringUtils.hasText(normalized) ? truncate(normalized, MAX_TYPE_CHARS) : "CONCEPT";
    }

    /** 别名列表转 JSON 数组字符串；空列表或序列化失败返回 null（JSON 列严禁空串）。 */
    private String toJsonArrayOrNull(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }
        return toJsonOrNull(aliases);
    }

    private String toJsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            return StringUtils.hasText(json) ? json : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /** 数值夹取到 [0,1]，null 回退默认值。 */
    private Double clamp(Double value, double fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String keyOf(String name) {
        return StringUtils.hasText(name) ? name.trim().toLowerCase() : "";
    }

    /** 去首尾空白后按上限截断，null 归一为空串。 */
    private String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars);
    }

    /** 应用关闭时优雅停机：最多等待 5 秒，超时或被中断则强制关闭。 */
    @PreDestroy
    void shutdownExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 抽取结果：三类条目计数 + 跳过原因（null 表示成功落库）。 */
    public record GraphExtractionResult(int entityCount, int relationCount, int factCount, String skipReason) {

        public static GraphExtractionResult skipped(String reason) {
            return new GraphExtractionResult(0, 0, 0, reason);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExtractionPayload(List<PayloadEntity> entities,
                             List<PayloadRelation> relations,
                             List<PayloadFact> facts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PayloadEntity(String name, String type, String description, List<String> aliases) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PayloadRelation(String source, String target, String relationType, Double weight) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PayloadFact(String subject, String predicate, String object, Double confidence) {
    }
}
