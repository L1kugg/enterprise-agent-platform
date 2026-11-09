package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MCP server 注册表合并语义：yml 为基础层，资产按名覆盖、禁用即移除、
 * 资产主机并入该 server 的 SSRF 白名单副本，畸形配置跳过不影响其余。
 */
class McpServerRegistryTest {

    private final AgentHarnessProperties properties = new AgentHarnessProperties();
    private final PlatformAssetMapper assetMapper = mock(PlatformAssetMapper.class);
    private McpServerRegistry registry;

    private static PlatformAsset asset(String name, String configJson) {
        return PlatformAsset.builder().id(1L).name(name).assetType("mcp-servers")
                .configJson(configJson).build();
    }

    @BeforeEach
    void setUp() {
        AgentHarnessProperties.McpServer demo = new AgentHarnessProperties.McpServer();
        demo.setBaseUrl("http://demo.internal:9101");
        demo.getTools().put("demo_tool", new AgentHarnessProperties.McpTool());
        properties.getMcp().getServers().put("demo", demo);
        properties.getMcp().setAllowedHosts(List.of("demo.internal"));
    }

    @Test
    void emptyAssetsFallBackToYmlServers() {
        registry = new McpServerRegistry(properties, assetMapper, new ObjectMapper());
        registry.refresh();

        assertThat(registry.view()).containsOnlyKeys("demo");
        assertThat(registry.find("demo")).isPresent();
        assertThat(registry.find("demo").orElseThrow().source()).isEqualTo("yml");
    }

    @Test
    void assetOverridesSameNameYmlServerAndMergesItsHost() {
        when(assetMapper.selectList(any())).thenReturn(List.of(asset("demo",
                "{\"baseUrl\":\"https://demo.example.com\",\"tools\":{\"ask\":{\"timeoutMs\":120000}}}")));
        registry = new McpServerRegistry(properties, assetMapper, new ObjectMapper());
        registry.refresh();

        McpServerRegistry.ResolvedServer resolved = registry.find("demo").orElseThrow();
        assertThat(resolved.source()).isEqualTo("asset");
        assertThat(resolved.baseUrl()).isEqualTo("https://demo.example.com");
        assertThat(resolved.toolTimeouts()).containsEntry("ask", 120000);
        // 资产主机并入该 server 的 SSRF 白名单副本（静态名单保持不变）
        assertThat(resolved.mcpConfig().getAllowedHosts()).contains("demo.internal", "demo.example.com");
        assertThat(properties.getMcp().getAllowedHosts()).containsExactly("demo.internal");
    }

    @Test
    void disabledAssetRemovesSameNameEntry() {
        when(assetMapper.selectList(any())).thenReturn(List.of(asset("demo",
                "{\"baseUrl\":\"https://demo.example.com\",\"enabled\":false}")));
        registry = new McpServerRegistry(properties, assetMapper, new ObjectMapper());
        registry.refresh();

        assertThat(registry.view()).doesNotContainKey("demo");
    }

    @Test
    void newAssetNameIsAddedAlongsideYmlServers() {
        when(assetMapper.selectList(any())).thenReturn(List.of(asset("verify-mcp",
                "{\"baseUrl\":\"https://mcp.deepwiki.com\",\"endpoint\":\"/mcp\","
                        + "\"tools\":{\"ask_wiki_question\":{\"timeoutMs\":120000},\"read\":{}}}")));
        registry = new McpServerRegistry(properties, assetMapper, new ObjectMapper());
        registry.refresh();

        assertThat(registry.view()).containsKeys("demo", "verify-mcp");
        McpServerRegistry.ResolvedServer resolved = registry.find("verify-mcp").orElseThrow();
        // 无 timeoutMs 的工具条目不入超时表（但 server 可被发现）
        assertThat(resolved.toolTimeouts()).containsOnlyKeys("ask_wiki_question");
        assertThat(resolved.mcpConfig().getAllowedHosts()).contains("mcp.deepwiki.com");
    }

    @Test
    void malformedAssetConfigIsSkipped() {
        when(assetMapper.selectList(any())).thenReturn(List.of(asset("broken", "not-json")));
        registry = new McpServerRegistry(properties, assetMapper, new ObjectMapper());
        registry.refresh();

        assertThat(registry.view()).containsOnlyKeys("demo");
    }
}
