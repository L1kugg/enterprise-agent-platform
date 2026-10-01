package com.enterprise.iqk.graph;

import com.enterprise.iqk.config.properties.GraphProperties;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.service.TenantCostService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphExtractionServiceTest {

    private static final ModelRouter.ModelRouteDecision DECISION = new ModelRouter.ModelRouteDecision(
            "economy", "test-mini", "economy", false, null, null, null, null);

    /** 两个实体 + 一条关系 + 两条事实（一条缺 confidence，一条缺 predicate）。 */
    private static final String FULL_PAYLOAD = """
            {"entities":[
               {"name":"热射病","type":"CONCEPT","description":"重度中暑","aliases":["重症中暑"]},
               {"name":"高温天气","type":"CONCEPT","description":"气温过高的天气"}],
             "relations":[
               {"source":"热射病","target":"高温天气","relationType":"CAUSED_BY","weight":0.9},
               {"source":"未知实体","target":"热射病","relationType":"RELATED_TO"},
               {"source":"热射病","target":"热射病","relationType":"SELF"}],
             "facts":[
               {"subject":"热射病","predicate":"预防方式","object":"避免高温时段外出","confidence":0.9},
               {"subject":"高温天气","object":"多补水"}]}
            """;

    private KgEntityMapper entityMapper;
    private KgRelationMapper relationMapper;
    private KgFactMapper factMapper;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ModelRouter modelRouter;
    private TenantCostService tenantCostService;
    private GraphProperties graphProperties;
    private GraphExtractionService service;

    @BeforeEach
    void setUp() {
        setUpWithContent(FULL_PAYLOAD);
    }

    private void setUpWithContent(String llmContent) {
        entityMapper = mock(KgEntityMapper.class);
        relationMapper = mock(KgRelationMapper.class);
        factMapper = mock(KgFactMapper.class);
        modelRouter = mock(ModelRouter.class);
        tenantCostService = mock(TenantCostService.class);
        graphProperties = new GraphProperties();

        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(llmContent);

        when(modelRouter.resolve(anyString(), anyString(), anyString(), anyString())).thenReturn(DECISION);
        // 默认：无同名实体可复用、无既有同向边
        when(entityMapper.searchByName(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        when(relationMapper.findDirectRelation(anyString(), anyString(), anyString()))
                .thenReturn(List.of());

        service = new GraphExtractionService(entityMapper, relationMapper, factMapper, graphProperties,
                chatClient, modelRouter, tenantCostService, new ObjectMapper());
    }

    @Test
    void storesEntitiesRelationsAndFactsFromFullPayload() {
        GraphExtractionService.GraphExtractionResult result =
                service.extractAndStore("tenant-1", "chat-1", "job-1", List.of("切片文本一", "切片文本二"));

        assertThat(result.skipReason()).isNull();
        assertThat(result.entityCount()).isEqualTo(2);
        assertThat(result.relationCount()).isEqualTo(1);
        assertThat(result.factCount()).isEqualTo(2);

        ArgumentCaptor<KgEntityRecord> entityCaptor = ArgumentCaptor.forClass(KgEntityRecord.class);
        verify(entityMapper, times(2)).insert(entityCaptor.capture());
        KgEntityRecord entity = entityCaptor.getAllValues().get(0);
        assertThat(entity.getEntityId()).startsWith("kg-ent-");
        assertThat(entity.getTenantId()).isEqualTo("tenant-1");
        assertThat(entity.getSourceId()).isEqualTo("chat-1");
        assertThat(entity.getName()).isEqualTo("热射病");
        assertThat(entity.getType()).isEqualTo("CONCEPT");
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
        // aliases 序列化为合法 JSON 数组（严禁空串）
        assertThat(entity.getAliases()).isEqualTo("[\"重症中暑\"]");

        ArgumentCaptor<KgRelationRecord> relationCaptor = ArgumentCaptor.forClass(KgRelationRecord.class);
        verify(relationMapper).insert(relationCaptor.capture());
        KgRelationRecord relation = relationCaptor.getValue();
        assertThat(relation.getRelationId()).startsWith("kg-rel-");
        assertThat(relation.getEvidenceId()).isEqualTo("chat-1");
        // 端点解析为（新插入实体的）entityId；未知端点与自环被丢弃
        assertThat(relation.getSourceEntityId()).isEqualTo(entity.getEntityId());
        assertThat(relation.getTargetEntityId()).isEqualTo(entityCaptor.getAllValues().get(1).getEntityId());
        assertThat(relation.getWeight()).isEqualTo(0.9);
        assertThat(relation.getCreatedAt()).isNotNull();

        ArgumentCaptor<KgFactRecord> factCaptor = ArgumentCaptor.forClass(KgFactRecord.class);
        verify(factMapper, times(2)).insert(factCaptor.capture());
        KgFactRecord first = factCaptor.getAllValues().get(0);
        assertThat(first.getFactId()).startsWith("kg-fact-");
        assertThat(first.getSource()).isEqualTo("chat-1");
        assertThat(first.getConfidence()).isEqualTo(0.9);
        KgFactRecord second = factCaptor.getAllValues().get(1);
        // 缺省谓语回退"相关"、缺省置信度回退 0.8
        assertThat(second.getPredicate()).isEqualTo("相关");
        assertThat(second.getConfidence()).isEqualTo(0.8);
        assertThat(second.getCreatedAt()).isNotNull();
        assertThat(second.getUpdatedAt()).isNotNull();

        // 抽取调用计入租户成本（kg_extraction 标签）
        verify(tenantCostService).recordUsage(eq("tenant-1"), eq("economy"), anyLong(), anyLong(),
                eq("kg_extraction"));
    }

    @Test
    void deletesOldRowsBeforeInserting() {
        service.extractAndStore("tenant-1", "chat-1", "job-1", List.of("切片文本"));

        // 写前删：先按作用域清边/事实/点，再落实体
        InOrder inOrder = inOrder(entityMapper, relationMapper, factMapper);
        inOrder.verify(relationMapper).deleteByEvidence("tenant-1", "chat-1");
        inOrder.verify(factMapper).deleteBySource("tenant-1", "chat-1");
        inOrder.verify(entityMapper).deleteBySource("tenant-1", "chat-1");
        inOrder.verify(entityMapper).insert(any(KgEntityRecord.class));
    }

    @Test
    void reusesExistingEntityAcrossDocuments() {
        KgEntityRecord existing = KgEntityRecord.builder()
                .entityId("kg-ent-existing").tenantId("tenant-1").name("热射病").build();
        when(entityMapper.searchByName(eq("tenant-1"), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(existing));

        GraphExtractionService.GraphExtractionResult result =
                service.extractAndStore("tenant-1", "chat-1", "job-1", List.of("切片文本"));

        // 只插入第二个实体，同名"热射病"复用既有 entityId
        assertThat(result.entityCount()).isEqualTo(2);
        ArgumentCaptor<KgEntityRecord> entityCaptor = ArgumentCaptor.forClass(KgEntityRecord.class);
        verify(entityMapper, times(1)).insert(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getName()).isEqualTo("高温天气");

        ArgumentCaptor<KgRelationRecord> relationCaptor = ArgumentCaptor.forClass(KgRelationRecord.class);
        verify(relationMapper).insert(relationCaptor.capture());
        assertThat(relationCaptor.getValue().getSourceEntityId()).isEqualTo("kg-ent-existing");
    }

    @Test
    void skipsRelationWhenDirectedEdgeAlreadyExists() {
        when(relationMapper.findDirectRelation(anyString(), anyString(), anyString()))
                .thenReturn(List.of(KgRelationRecord.builder().relationId("kg-rel-old").build()));

        GraphExtractionService.GraphExtractionResult result =
                service.extractAndStore("tenant-1", "chat-1", "job-1", List.of("切片文本"));

        assertThat(result.relationCount()).isZero();
        verify(relationMapper, never()).insert(any(KgRelationRecord.class));
    }

    @Test
    void enforcesEntityCapacityCap() {
        graphProperties.setMaxEntities(1);

        GraphExtractionService.GraphExtractionResult result =
                service.extractAndStore("tenant-1", "chat-1", "job-1", List.of("切片文本"));

        assertThat(result.entityCount()).isEqualTo(1);
        verify(entityMapper, times(1)).insert(any(KgEntityRecord.class));
        // 实体容量帽连带限制关系抽取：第二个实体未入库，其关系端点未知
        verify(relationMapper, never()).insert(any(KgRelationRecord.class));
    }

    @Test
    void returnsParseSkipForNonJsonResponse() {
        setUpWithContent("这不是 JSON，模型偶尔会这样回答");

        GraphExtractionService.GraphExtractionResult result =
                service.extractAndStore("tenant-1", "chat-1", "job-1", List.of("切片文本"));

        assertThat(result.skipReason()).isEqualTo("parse");
        verify(entityMapper, never()).insert(any(KgEntityRecord.class));
        // 解析失败不产生写入，但也不清空旧数据（写前删在解析之后）
        verify(entityMapper, never()).deleteBySource(anyString(), anyString());
    }

    @Test
    void swallowsLlmFailureWithoutPropagating() {
        setUpWithContent("unused");
        when(chatClient.prompt()).thenThrow(new RuntimeException("llm down"));

        assertThatCode(() -> {
            GraphExtractionService.GraphExtractionResult result =
                    service.extractAndStore("tenant-1", "chat-1", "job-1", List.of("切片文本"));
            assertThat(result.skipReason()).isEqualTo("error");
        }).doesNotThrowAnyException();
        verify(entityMapper, never()).insert(any(KgEntityRecord.class));
    }

    @Test
    void returnsBudgetSkipWhenBudgetGateRejects() {
        setUpWithContent(FULL_PAYLOAD);
        doThrow(new RuntimeException("over budget"))
                .when(tenantCostService).assertBudget(anyString(), anyString(), anyLong(), anyLong());

        GraphExtractionService.GraphExtractionResult result =
                service.extractAndStore("tenant-1", "chat-1", "job-1", List.of("切片文本"));

        assertThat(result.skipReason()).isEqualTo("budget");
        verify(chatClient, never()).prompt();
        verify(entityMapper, never()).insert(any(KgEntityRecord.class));
    }

    @Test
    void submitAsyncIsNoOpWhenDisabledOrEmpty() {
        graphProperties.setExtractionEnabled(false);
        service.submitAsync("tenant-1", "chat-1", "job-1", List.of("切片文本"));

        service.submitAsync("tenant-1", "chat-1", "job-1", List.of());
        service.submitAsync("tenant-1", "chat-1", "job-1", null);

        verify(chatClient, never()).prompt();
    }

    @Test
    void returnsEmptyInputSkipWithoutCallingModel() {
        GraphExtractionService.GraphExtractionResult result =
                service.extractAndStore("tenant-1", "chat-1", "job-1", List.of("  ", ""));

        assertThat(result.skipReason()).isEqualTo("empty-input");
        verify(chatClient, never()).prompt();
    }

    @Test
    void deleteByChatRemovesInRelationFactEntityOrder() {
        when(relationMapper.deleteByEvidence("tenant-1", "chat-1")).thenReturn(2);
        when(factMapper.deleteBySource("tenant-1", "chat-1")).thenReturn(3);
        when(entityMapper.deleteBySource("tenant-1", "chat-1")).thenReturn(1);

        int removed = service.deleteByChat("tenant-1", "chat-1");

        assertThat(removed).isEqualTo(6);
        InOrder inOrder = inOrder(relationMapper, factMapper, entityMapper);
        inOrder.verify(relationMapper).deleteByEvidence("tenant-1", "chat-1");
        inOrder.verify(factMapper).deleteBySource("tenant-1", "chat-1");
        inOrder.verify(entityMapper).deleteBySource("tenant-1", "chat-1");
    }

    @Test
    void parsePayloadToleratesCodeFencedJson() {
        setUpWithContent("unused");

        GraphExtractionService.ExtractionPayload payload = service.parsePayload(
                "```json\n{\"entities\":[],\"relations\":[],\"facts\":[]}\n```");

        assertThat(payload).isNotNull();
    }

    @Test
    void inputTextTruncatedToConfiguredLimit() {
        graphProperties.setMaxInputChars(50);

        service.extractAndStore("tenant-1", "chat-1", "job-1", List.of("长".repeat(200)));

        // 送模型的用户消息 = 前缀 + 截断到配置上限的切片拼接文本
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userCaptor.capture());
        assertThat(userCaptor.getValue())
                .startsWith("文档片段：")
                .hasSize("文档片段：\n".length() + 50);
    }
}
