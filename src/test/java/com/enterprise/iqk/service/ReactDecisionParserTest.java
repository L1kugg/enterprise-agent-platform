package com.enterprise.iqk.service;

import com.enterprise.iqk.agent.harness.ActionSchemaRegistry;
import com.enterprise.iqk.agent.harness.PlannerActionCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReactDecisionParserTest {
    private final ReactDecisionParser parser = new ReactDecisionParser(
            new PlannerActionCatalog(new ActionSchemaRegistry()), new ObjectMapper());

    @Test
    void parsesAllowedActionAndInputFromModelJson() {
        ReactDecisionParser.ReasonDecision decision = parser.parse("""
                planner: {"thought":"look up documents","action":"rag_search", "action_input":{"query":"RAG"}}
                """);

        assertThat(decision.action()).isEqualTo("rag_search");
        assertThat(decision.thought()).isEqualTo("look up documents");
        assertThat(decision.actionInput()).isEqualTo(Map.of("query", "RAG"));
    }

    @Test
    void parsesMcpCallActionWithinWhitelist() {
        // mcp_call 在白名单内：模型规划查天气时不被强制归为 finish
        ReactDecisionParser.ReasonDecision decision = parser.parse("""
                {"thought":"用户想查天气","action":"mcp_call",
                 "action_input":{"server":"weather","tool":"get_weather","arguments":{"city":"长春"}}}
                """);

        assertThat(decision.action()).isEqualTo("mcp_call");
        assertThat(decision.actionInput()).containsEntry("server", "weather")
                .containsEntry("tool", "get_weather");
    }

    @Test
    void parsesQueryDatabaseActionWithinWhitelist() {
        // query_database 在白名单内：模型规划查数据库时不被强制归为 finish
        ReactDecisionParser.ReasonDecision decision = parser.parse("""
                {"thought":"用户想查课程数","action":"query_database",
                 "action_input":{"sql":"SELECT COUNT(*) AS total FROM course WHERE tenant_id = 'public'"}}
                """);

        assertThat(decision.action()).isEqualTo("query_database");
        assertThat(decision.actionInput()).containsEntry("sql",
                "SELECT COUNT(*) AS total FROM course WHERE tenant_id = 'public'");
    }

    @Test
    void convertsUnknownOrInvalidModelOutputToSafeFinish() {
        ReactDecisionParser.ReasonDecision unknown = parser.parse("{" +
                "\"action\":\"delete_everything\",\"answer\":\"safe\"}");
        ReactDecisionParser.ReasonDecision invalid = parser.parse("not-json-answer");
        ReactDecisionParser.ReasonDecision malformed = parser.parse("{not-json}");
        ReactDecisionParser.ReasonDecision blank = parser.parse("");
        ReactDecisionParser.ReasonDecision blankAction = parser.parse("{\"action\":\"\"}");

        assertThat(unknown.action()).isEqualTo("finish");
        assertThat(unknown.answer()).isEqualTo("safe");
        assertThat(invalid.action()).isEqualTo("finish");
        assertThat(invalid.answer()).isEqualTo("not-json-answer");
        assertThat(malformed.action()).isEqualTo("finish");
        assertThat(blank.answer()).isEmpty();
        assertThat(blankAction.action()).isEqualTo("finish");
    }

    @Test
    void providesDeterministicFallbacksForSafetyAndRetrievalQuestions() {
        ReactDecisionParser.ReasonDecision heat = parser.fallback("高温健康风险有哪些？");
        ReactDecisionParser.ReasonDecision noContext = parser.fallback("知识库里没有答案怎么办？");
        ReactDecisionParser.ReasonDecision knowledgeBase = parser.fallback("请从知识库引用来源");
        ReactDecisionParser.ReasonDecision school = parser.fallback("校区怎么查询？");
        ReactDecisionParser.ReasonDecision reservation = parser.fallback("课程预约需要哪些字段？");
        ReactDecisionParser.ReasonDecision generic = parser.fallback("你好");

        assertThat(heat.action()).isEqualTo("finish");
        assertThat(heat.answer()).contains("中暑", "脱水");
        assertThat(noContext.answer()).contains("不会虚构");
        assertThat(knowledgeBase.action()).isEqualTo("rag_search");
        assertThat(knowledgeBase.actionInput()).containsEntry("query", "请从知识库引用来源");
        assertThat(school.answer()).contains("校区查询");
        assertThat(reservation.answer()).contains("课程预约");
        assertThat(generic.answer()).contains("规划器暂不可用");
    }

    @Test
    void rejectsBlankPromptWithAUserFacingFallback() {
        ReactDecisionParser.ReasonDecision decision = parser.fallback("  ");

        assertThat(decision.action()).isEqualTo("finish");
        assertThat(decision.answer()).contains("请求内容为空");
        assertThat(decision.citations()).anyMatch(citation -> citation.contains("fallback://input_validation"));
    }
}
