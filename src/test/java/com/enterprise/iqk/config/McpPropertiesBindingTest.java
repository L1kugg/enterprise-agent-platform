package com.enterprise.iqk.config;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** MCP server yml 绑定验证：map 键带下划线 + timeout-ms 宽松绑定必须落到 McpTool.timeoutMs。 */
class McpPropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties(AgentHarnessProperties.class)
    static class PropsConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropsConfig.class)
            .withPropertyValues(
                    "app.agent-harness.mcp.allowed-hosts=mcp.deepwiki.com",
                    "app.agent-harness.mcp.servers.deepwiki.base-url=https://mcp.deepwiki.com",
                    "app.agent-harness.mcp.servers.deepwiki.endpoint=/mcp",
                    "app.agent-harness.mcp.servers.deepwiki.tools.ask_wiki_question.timeout-ms=120000",
                    "app.agent-harness.mcp.servers.context7.tools.get-library-docs.timeout-ms=30000");

    @Test
    void serverToolsMapBindsWithUnderscoreKeysAndTimeout() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AgentHarnessProperties properties = context.getBean(AgentHarnessProperties.class);
            AgentHarnessProperties.McpServer deepwiki =
                    properties.getMcp().getServers().get("deepwiki");
            assertThat(deepwiki).isNotNull();
            assertThat(deepwiki.getBaseUrl()).isEqualTo("https://mcp.deepwiki.com");
            assertThat(deepwiki.getTools()).containsKey("ask_wiki_question");
            assertThat(deepwiki.getTools().get("ask_wiki_question").getTimeoutMs())
                    .isEqualTo(120000);
            assertThat(properties.getMcp().getAllowedHosts())
                    .isEqualTo(List.of("mcp.deepwiki.com"));
        });
    }
}
