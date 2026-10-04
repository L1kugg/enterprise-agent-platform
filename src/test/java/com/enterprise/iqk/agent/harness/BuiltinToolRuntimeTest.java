package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.rag.HybridRagAnswerService;
import com.enterprise.iqk.retrieval.CitationItem;
import com.enterprise.iqk.retrieval.EvidenceItem;
import com.enterprise.iqk.tools.CourseTools;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BuiltinToolRuntimeTest {

    @Test
    void executeRagSearchReturnsTraceFriendlyObservation() {
        CourseTools courseTools = mock(CourseTools.class);
        HybridRagAnswerService hybridRagAnswerService = mock(HybridRagAnswerService.class);
        // 前端色条按 Map 插入序绘制，weights 必须保持 vector/keyword/graph/web 顺序
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("vector", 0.4);
        weights.put("keyword", 0.25);
        weights.put("graph", 0.2);
        weights.put("web", 0.15);
        when(hybridRagAnswerService.answer(
                eq("java cache"),
                eq("tenant-a"),
                eq("chat-1"),
                eq("react::chat-1"),
                eq("balanced")
        )).thenReturn(HybridRagAnswerService.HybridRagResult.builder()
                .answer("answer")
                .citations(List.of(CitationItem.builder()
                        .index(1).sourceType("vector").title("doc")
                        .chunkId("chunk-1").confidence(0.8).excerpt("摘录").build()))
                .evidence(List.of(EvidenceItem.builder()
                        .sourceType("vector").title("doc").chunkId("chunk-1")
                        .score(0.8).snippet("evidence").build()))
                .weights(weights)
                .build());
        BuiltinToolRuntime runtime = new BuiltinToolRuntime(courseTools, hybridRagAnswerService);

        AgentObservation observation = runtime.execute(new AgentAction(
                "rag_search",
                Map.of("query", "java cache"),
                "fallback prompt",
                "tenant-a",
                "chat-1",
                "balanced",
                "task-1",
                "step-1"
        ));

        Map<String, Object> payload = observation.toMap();
        assertThat(payload)
                .containsEntry("status", "success")
                .containsEntry("query", "java cache")
                .containsEntry("answer", "answer")
                .containsEntry("source", "builtin");
        // citations 是前端 parseCitation 可解析的文本（source=标题, chunk=块号）
        assertThat(payload.get("citations")).isEqualTo(List.of("source=doc, chunk=chunk-1"));
        assertThat(payload.get("evidence")).isEqualTo(List.of("evidence"));
        // 当次实际召回路权重随观测回传（四路归一化值），前端轨迹条照此绘制
        assertThat(payload.get("weights")).isEqualTo(weights);
    }

    @Test
    void executeRagSearchFiltersBlankEvidenceAndEscapesCommasInCitationTitle() {
        CourseTools courseTools = mock(CourseTools.class);
        HybridRagAnswerService hybridRagAnswerService = mock(HybridRagAnswerService.class);
        when(hybridRagAnswerService.answer(
                eq("q"), eq("tenant-a"), eq("chat-1"), eq("react::chat-1"), eq("balanced")
        )).thenReturn(HybridRagAnswerService.HybridRagResult.builder()
                .answer("answer")
                // 标题含半角逗号会截断前端正则 source=[^,]+，必须转全角
                .citations(List.of(CitationItem.builder()
                        .index(1).sourceType("vector").title("报告, 2024版")
                        .chunkId("chunk-1").build()))
                .evidence(List.of(
                        EvidenceItem.builder().snippet("有效证据").build(),
                        EvidenceItem.builder().snippet("   ").build()))
                .build());
        BuiltinToolRuntime runtime = new BuiltinToolRuntime(courseTools, hybridRagAnswerService);

        AgentObservation observation = runtime.execute(new AgentAction(
                "rag_search",
                Map.of("query", "q"),
                "fallback prompt",
                "tenant-a",
                "chat-1",
                "balanced",
                "task-1",
                "step-1"
        ));

        Map<String, Object> payload = observation.toMap();
        assertThat(payload.get("citations")).isEqualTo(List.of("source=报告， 2024版, chunk=chunk-1"));
        assertThat(payload.get("evidence")).isEqualTo(List.of("有效证据"));
    }

    @Test
    void executeReservationRejectsMissingRequiredFields() {
        CourseTools courseTools = mock(CourseTools.class);
        HybridRagAnswerService hybridRagAnswerService = mock(HybridRagAnswerService.class);
        BuiltinToolRuntime runtime = new BuiltinToolRuntime(courseTools, hybridRagAnswerService);

        AgentObservation observation = runtime.execute(new AgentAction(
                "add_course_reservation",
                Map.of("course", "Java"),
                "prompt",
                "tenant-a",
                "chat-1",
                "balanced",
                "",
                ""
        ));

        assertThat(observation.toMap())
                .containsEntry("status", "error")
                .containsEntry("source", "builtin")
                .containsEntry("message", "missing required fields for reservation");
        verifyNoInteractions(courseTools, hybridRagAnswerService);
    }

    @Test
    void executeSchoolQueryDelegatesToCourseTools() {
        CourseTools courseTools = mock(CourseTools.class);
        HybridRagAnswerService hybridRagAnswerService = mock(HybridRagAnswerService.class);
        when(courseTools.querySchool()).thenReturn(List.of());
        BuiltinToolRuntime runtime = new BuiltinToolRuntime(courseTools, hybridRagAnswerService);

        AgentObservation observation = runtime.execute(new AgentAction(
                "query_school",
                Map.of(),
                "prompt",
                "tenant-a",
                "chat-1",
                "balanced",
                "",
                ""
        ));

        assertThat(observation.toMap())
                .containsEntry("status", "success")
                .containsEntry("data", List.of());
        verify(courseTools).querySchool();
        verifyNoInteractions(hybridRagAnswerService);
    }
}
