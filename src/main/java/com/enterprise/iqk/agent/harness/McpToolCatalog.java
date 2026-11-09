package com.enterprise.iqk.agent.harness;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具目录：启动时对所有已配置的 MCP server 调 tools/list，
 * 发现可用工具并缓存定义（名称、描述、参数 schema）。
 * 规划器提示词的 mcp_call 说明从本目录动态生成——新增标准 MCP server
 * 只需配 base-url，工具经 tools/list 自动出现在 Agent 的可用动作列表中。
 * 单个 server 发现失败不阻断启动（该 server 的工具从目录缺席，
 * supports() 仍以 yml 白名单为准）。
 */
@Slf4j
@Component
public class McpToolCatalog implements ApplicationRunner {

    private final McpServerRegistry serverRegistry;
    private final List<McpToolAdapter> adapters;

    /** server → 该 server 的已发现工具列表 */
    private final Map<String, List<DiscoveredTool>> catalog = new LinkedHashMap<>();

    public McpToolCatalog(McpServerRegistry serverRegistry, List<McpToolAdapter> adapters) {
        this.serverRegistry = serverRegistry;
        this.adapters = adapters;
    }

    @Override
    public void run(ApplicationArguments args) {
        refresh();
    }

    /** 重新发现所有已配置 server 的工具（server 清单来自注册表合并视图） */
    public void refresh() {
        catalog.clear();
        serverRegistry.view().forEach((serverName, resolved) -> {
            if (!StringUtils.hasText(resolved.baseUrl())) {
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

    /** 生成规划器提示词中的 mcp_call 说明段（每工具一行，附可抄的 action_input 形状示例） */
    public String plannerHint() {
        return plannerHintOf(catalog);
    }

    static String plannerHintOf(Map<String, List<DiscoveredTool>> catalog) {
        if (catalog.isEmpty()) {
            return "查询外部工具（当前未发现可用工具）";
        }
        StringBuilder sb = new StringBuilder("调用外部工具（mcp_call）：上游工具的参数必须放在 arguments 对象内"
                + "（不要平铺在顶层，arguments 必须是对象不是字符串），action_input 形如 "
                + "{\"server\":\"名称\",\"tool\":\"工具名\",\"arguments\":{参数名:值}}。可选组合：");
        catalog.forEach((server, tools) -> {
            for (DiscoveredTool tool : tools) {
                sb.append("\n- ").append(server).append("/").append(tool.name());
                if (!tool.description().isBlank()) {
                    sb.append("：").append(truncate(tool.description(), 200));
                }
                sb.append("。action_input 示例：").append(exampleFor(server, tool));
            }
        });
        return sb.toString();
    }

    /** 按工具 inputSchema 生成最小可抄示例：required 键优先、properties 键补齐，最多 4 个参数键 */
    private static String exampleFor(String server, DiscoveredTool tool) {
        LinkedHashSet<String> keys = new LinkedHashSet<>(extractRequired(tool.inputSchema()));
        if (tool.inputSchema().get("properties") instanceof Map<?, ?> propMap) {
            propMap.keySet().stream().map(String::valueOf).forEach(keys::add);
        }
        StringBuilder example = new StringBuilder("{\"server\":\"").append(server)
                .append("\",\"tool\":\"").append(tool.name()).append("\",\"arguments\":{");
        int shown = 0;
        for (String key : keys) {
            if (shown >= 4) {
                break;
            }
            if (shown > 0) {
                example.append(',');
            }
            example.append('"').append(key).append("\":\"...\"");
            shown++;
        }
        return example.append("}}").toString();
    }

    private static String truncate(String text, int max) {
        String cleaned = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max) + "…";
    }

    /** 从 inputSchema 提取 required 字段列表 */
    @SuppressWarnings("unchecked")
    private static List<String> extractRequired(Map<String, Object> inputSchema) {
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
