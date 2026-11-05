package com.enterprise.iqk.agent.harness;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mcp_call 规划器提示词：必须给模型可照抄的 action_input 形状示例
 * （arguments 包裹说明 + 按工具 inputSchema 生成的参数键示例）。
 */
class McpToolCatalogHintTest {

    private static McpToolCatalog.DiscoveredTool tool(String name, String description,
                                                      Map<String, Object> inputSchema) {
        return new McpToolCatalog.DiscoveredTool(name, description, inputSchema);
    }

    @Test
    void hintContainsArgumentsWrapperRuleAndPerToolExample() {
        Map<String, List<McpToolCatalog.DiscoveredTool>> catalog = Map.of(
                "deepwiki", List.of(tool(
                        "ask_wiki_question",
                        "Ask any question about a repository's codebase and get an AI-powered answer",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "repoName", Map.of("type", "string"),
                                        "question", Map.of("type", "string")),
                                "required", List.of("repoName", "question")))));

        String hint = McpToolCatalog.plannerHintOf(catalog);

        // 包裹规则一句话说清：参数放 arguments 对象内、不平铺、不是字符串
        assertThat(hint).contains("必须放在 arguments 对象内");
        assertThat(hint).contains("不要平铺在顶层");
        assertThat(hint).contains("不是字符串");
        // 每工具带完整可抄示例，required 键按 schema 顺序出现
        assertThat(hint).contains("deepwiki/ask_wiki_question");
        assertThat(hint).contains("{\"server\":\"deepwiki\",\"tool\":\"ask_wiki_question\","
                + "\"arguments\":{\"repoName\":\"...\",\"question\":\"...\"}}");
    }

    @Test
    void emptyCatalogFallsBackToNoneHint() {
        assertThat(McpToolCatalog.plannerHintOf(Map.of())).contains("未发现可用工具");
    }

    @Test
    void longDescriptionIsTruncated() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            longText.append("很长的描述文字");
        }
        Map<String, List<McpToolCatalog.DiscoveredTool>> catalog = Map.of(
                "ctx", List.of(tool("some_tool", longText.toString(), Map.of())));

        String hint = McpToolCatalog.plannerHintOf(catalog);

        assertThat(hint).contains("…");
        assertThat(hint.length()).isLessThan(600);
    }

    @Test
    void exampleKeysComeFromPropertiesWhenRequiredMissing() {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("context7CompatibleLibraryID", Map.of("type", "string"));
        properties.put("tokens", Map.of("type", "number"));
        properties.put("topic", Map.of("type", "string"));
        properties.put("extra1", Map.of("type", "string"));
        properties.put("extra2", Map.of("type", "string"));
        Map<String, List<McpToolCatalog.DiscoveredTool>> catalog = Map.of(
                "context7", List.of(tool(
                        "get-library-docs",
                        "Fetch documentation",
                        Map.of("type", "object", "properties", properties))));

        String hint = McpToolCatalog.plannerHintOf(catalog);

        // 无 required 时按 properties 声明顺序取键且最多 4 个
        assertThat(hint).contains("\"arguments\":{\"context7CompatibleLibraryID\":\"...\"");
        assertThat(hint).doesNotContain("extra2");
    }
}
