package com.enterprise.iqk.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动作输入归一化：平铺参数收编进 arguments / JSON 字符串 arguments 解析为对象 /
 * 规范输入原样返回（同一实例）/ 非 arguments 动作零影响。
 */
class ActionInputNormalizerTest {

    private final ActionInputNormalizer normalizer = new ActionInputNormalizer(
            new ActionSchemaRegistry(), new ObjectMapper());

    private AgentAction action(String name, Map<String, Object> input) {
        return new AgentAction(name, input, "prompt", "tenant", "chat", "balanced", "", "");
    }

    @Test
    void flattenedTopLevelParamsAreCollectedIntoArguments() {
        // 模型把上游工具参数平铺在顶层（提示词只列了参数名的自然后果）
        Map<String, Object> input = new HashMap<>();
        input.put("server", "deepwiki");
        input.put("tool", "ask_wiki_question");
        input.put("repoName", "1Panel-dev/MaxKB");
        input.put("question", "架构是什么");

        AgentAction normalized = normalizer.normalize(action("mcp_call", input));

        assertThat(normalized).isNotSameAs(action("mcp_call", input));
        assertThat(normalized.actionInput().get("server")).isEqualTo("deepwiki");
        assertThat(normalized.actionInput().get("tool")).isEqualTo("ask_wiki_question");
        assertThat(normalized.actionInput().get("arguments")).isEqualTo(Map.of(
                "repoName", "1Panel-dev/MaxKB",
                "question", "架构是什么"));
        // 平铺字段不再残留顶层（守卫的 unknownFields 检查因此放行）
        assertThat(normalized.actionInput().keySet()).containsExactlyInAnyOrder(
                "server", "tool", "arguments");
    }

    @Test
    void stringArgumentsAreParsedIntoObject() {
        Map<String, Object> input = Map.of(
                "server", "deepwiki",
                "tool", "read_wiki_structure",
                "arguments", "{\"repoName\":\"1Panel-dev/MaxKB\"}");

        AgentAction normalized = normalizer.normalize(action("mcp_call", input));

        assertThat(normalized.actionInput().get("arguments"))
                .isEqualTo(Map.of("repoName", "1Panel-dev/MaxKB"));
    }

    @Test
    void unparseableStringArgumentsStayUntouched() {
        AgentAction original = action("mcp_call", Map.of(
                "server", "deepwiki",
                "tool", "read_wiki_structure",
                "arguments", "not-json"));

        AgentAction normalized = normalizer.normalize(original);

        // 解析失败保持原样（同一实例）：守卫已放行（字符串非空白），runtime 降级空参由上游诚实报错
        assertThat(normalized).isSameAs(original);
    }

    @Test
    void wellFormedInputIsReturnedAsSameInstance() {
        AgentAction original = action("mcp_call", Map.of(
                "server", "deepwiki",
                "tool", "ask_wiki_question",
                "arguments", Map.of("repoName", "1Panel-dev/MaxKB", "question", "架构")));

        assertThat(normalizer.normalize(original)).isSameAs(original);
    }

    @Test
    void toolCallFlattenedParamsAreNormalizedToo() {
        Map<String, Object> input = new HashMap<>();
        input.put("tool", "汇率查询");
        input.put("currency", "USD");

        AgentAction normalized = normalizer.normalize(action("tool_call", input));

        assertThat(normalized.actionInput().get("tool")).isEqualTo("汇率查询");
        assertThat(normalized.actionInput().get("arguments")).isEqualTo(Map.of("currency", "USD"));
    }

    @Test
    void actionsWithoutArgumentsRequirementAreUntouched() {
        AgentAction original = action("query_database", Map.of("sql", "SELECT 1"));

        assertThat(normalizer.normalize(original)).isSameAs(original);
    }

    @Test
    void emptyInputIsUntouched() {
        AgentAction original = action("mcp_call", Map.of());

        assertThat(normalizer.normalize(original)).isSameAs(original);
    }
}
