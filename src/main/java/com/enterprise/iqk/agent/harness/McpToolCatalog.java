package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具目录：启动时对所有已配置的 MCP server 调 tools/list，
 * 发现可用工具并缓存定义（名称、描述、参数 schema）。
 * 规划器提示词的 mcp_call 说明从本目录动态生成——新增 MCP server
 * 只需配 base-url，工具自动出现在 Agent 的可用动作列表中。
 * 单个 server 发现失败不阻断启动（该 server 的工具退回 yml 静态配置）。
 */
@Slf4j
@Component
public class McpToolCatalog implements ApplicationRunner {

    private final AgentHarnessProperties harnessProperties;
    private final List<McpToolAdapter> adapters;

    /** server → 该 server 的已发现工具列表 */
    private final Map<String, List<DiscoveredTool>> catalog = new LinkedHashMap<>();

    public McpToolCatalog(AgentHarnessProperties harnessProperties, List<McpToolAdapter> adapters) {
        this.harnessProperties = harnessProperties;
        this.adapters = adapters;
    }

    @Override
    public void run(ApplicationArguments args) {
        refresh();
    }

    /** 重新发现所有已配置 server 的工具 */
    public void refresh() {
        catalog.clear();
        harnessProperties.getMcp().getServers().forEach((serverName, serverConfig) -> {
            if (!serverConfig.isEnabled() || serverConfig.getBaseUrl() == null || serverConfig.getBaseUrl().isBlank()) {
                return;
            }
            List<DiscoveredTool> tools = discoverServerTools(serverName);
            if (!tools.isEmpty()) {
                catalog.put(serverName, tools);
                log.info("MCP tools discovered: server={}, count={}", serverName, tools.size());
            }
        });
    }

    /** 从所有适配器中找能发现该 server 工具的适配器并调用 listTools */
    private List<DiscoveredTool> discoverServerTools(String server) {
        for (McpToolAdapter adapter : adapters) {
            List<Map<String, Object>> rawTools = adapter.listTools(server);
            if (!rawTools.isEmpty()) {
                return rawTools.stream().map(this::toDiscoveredTool).toList();
            }
        }
        return List.of();
    }

    /** JSON-RPC tools/list 返回的工具定义 → 内部结构 */
    @SuppressWarnings("unchecked")
    private DiscoveredTool toDiscoveredTool(Map<String, Object> raw) {
        String name = String.valueOf(raw.getOrDefault("name", ""));
        String description = String.valueOf(raw.getOrDefault("description", ""));
        Map<String, Object> inputSchema = raw.get("inputSchema") instanceof Map<?, ?> schema
                ? (Map<String, Object>) schema : Map.of();
        return new DiscoveredTool(name, description, inputSchema);
    }

    /** 生成规划器提示词中的 mcp_call 说明段（每工具一行） */
    public String plannerHint() {
        if (catalog.isEmpty()) {
            return "查询外部工具（当前未发现可用工具）";
        }
        StringBuilder sb = new StringBuilder("调用外部工具（mcp_call），可选 server/tool 组合：");
        catalog.forEach((server, tools) -> {
            for (DiscoveredTool tool : tools) {
                sb.append("\n- ").append(server).append("/").append(tool.name());
                if (!tool.description().isBlank()) {
                    sb.append("：").append(tool.description());
                }
                List<String> required = extractRequired(tool.inputSchema());
                if (!required.isEmpty()) {
                    sb.append("，必填参数：").append(String.join(", ", required));
                }
            }
        });
        return sb.toString();
    }

    /** 从 inputSchema 提取 required 字段列表 */
    @SuppressWarnings("unchecked")
    private List<String> extractRequired(Map<String, Object> inputSchema) {
        Object required = inputSchema.get("required");
        if (required instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /** 获取所有已发现的 server 名 */
    public List<String> servers() {
        return List.copyOf(catalog.keySet());
    }

    /** 获取指定 server 的已发现工具 */
    public List<DiscoveredTool> tools(String server) {
        return catalog.getOrDefault(server, List.of());
    }

    /** 已发现的工具定义 */
    public record DiscoveredTool(String name, String description, Map<String, Object> inputSchema) {
    }
}
