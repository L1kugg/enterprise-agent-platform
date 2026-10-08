package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.rag.HybridRagAnswerService;
import com.enterprise.iqk.retrieval.CitationItem;
import com.enterprise.iqk.retrieval.EvidenceItem;
import com.enterprise.iqk.tools.DatabaseQueryTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuiltinToolRuntimeTest {

    private HybridRagAnswerService ragService;
    private DatabaseQueryTools dbTools;
    private BuiltinToolRuntime runtime;

    @BeforeEach
    void setUp() {
        ragService = mock(HybridRagAnswerService.class);
        dbTools = mock(DatabaseQueryTools.class);
        runtime = new BuiltinToolRuntime(ragService, dbTools);
    }

    @Test
    void supportsOnlyEnterpriseActions() {
        assertThat(runtime.supports("create_task")).isTrue();
        assertThat(runtime.supports("rag_search")).isTrue();
        assertThat(runtime.supports("query_database")).isTrue();
        assertThat(runtime.supports("query_school")).isFalse();
        assertThat(runtime.supports("query_course")).isFalse();
    }

    @Test
    void createTaskReturnsConfirmation() {
        AgentObservation obs = runtime.execute(new AgentAction(
                "create_task",
                Map.of("title", "完成季度报告", "description", "整理Q3数据", "priority", "high"),
                "prompt", "tenant", "chat", "balanced", "task", "step", false));
        assertThat(obs.toMap()).containsEntry("status", "created");
        assertThat(obs.toMap())
                .containsEntry("taskTitle", "完成季度报告")
                .containsEntry("description", "整理Q3数据")
                .containsEntry("priority", "high");
    }

    @Test
    void createTaskRejectsMissingTitle() {
        AgentObservation obs = runtime.execute(new AgentAction(
                "create_task", Map.of("description", "no title"),
                "prompt", "tenant", "chat", "balanced", "task", "step", false));
        assertThat(obs.toMap()).containsEntry("status", "error");
    }

    @Test
    void ragSearchDelegatesToHybridService() {
        HybridRagAnswerService.HybridRagResult result = HybridRagAnswerService.HybridRagResult.builder()
                .answer("测试回答")
                .citations(List.of(CitationItem.builder().title("doc.pdf").chunkId("c1").build()))
                .evidence(List.of(EvidenceItem.builder().snippet("证据片段").build()))
                .weights(null)
                .build();
        when(ragService.answer(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(result);

        AgentObservation obs = runtime.execute(new AgentAction(
                "rag_search", Map.of("query", "测试"),
                "prompt", "tenant", "chat", "balanced", "task", "step", false));
        assertThat(obs.toMap()).containsEntry("status", "success");
    }

    @Test
    void databaseQueryDelegates() {
        when(dbTools.queryDatabase(anyString(), anyString()))
                .thenReturn(Map.of("status", "success", "rows", 5));
        AgentObservation obs = runtime.execute(new AgentAction(
                "query_database", Map.of("sql", "SELECT 1"),
                "prompt", "tenant", "chat", "balanced", "task", "step", false));
        assertThat(obs.toMap()).containsEntry("status", "success");
    }
}
