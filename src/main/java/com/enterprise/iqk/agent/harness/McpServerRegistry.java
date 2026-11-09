package com.enterprise.iqk.agent.harness;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MCP server 注册表：把"yml 静态配置"与"platform_asset 里的 mcp-servers 资产"
 * 合并成一份运行时视图——MCP 工具从此可在工具管理页动态注册（免改配置重启）。
 *
 * 合并规则：yml 配置为基础层；资产按名称覆盖同名 yml 项；资产 enabled=false
 * 表示显式移除该名称。资产 baseUrl 的主机自动并入该 server 的 SSRF 白名单
 * （仅此一项授权，内网地址仍需 allowed-internal-addresses 逐条声明）。
 *
 * 每个解析后的 server 携带一份专属 Mcp 配置副本（allowedHosts 已并入资产主机），
 * 传输层与 SSRF 校验按 server 独立消费，互不影响。
 * 变更传播链：资产增删改 → PlatformAssetService 钩子 → McpRuntimeRefresher.refreshAll()
 * （注册表重建 → 客户端缓存全清 → 目录重新发现）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpServerRegistry {

    public static final String ASSET_TYPE = "mcp-servers";

    private final AgentHarnessProperties harnessProperties;
    private final PlatformAssetMapper assetMapper;
    private final ObjectMapper objectMapper;

    private final Map<String, ResolvedServer> servers = new LinkedHashMap<>();

    /** 解析后的单个 server：yml 来源无 assetId；enabled=false 仅出现在覆盖移除的中间态，不会入视图。 */
    public record ResolvedServer(String name, String source, Long assetId,
                                 String baseUrl, String endpoint,
                                 Map<String, Integer> toolTimeouts,
                                 AgentHarnessProperties.Mcp mcpConfig) {
    }

    @PostConstruct
    void init() {
        refresh();
    }

    /** 重建合并视图：任何一步解析失败只跳过该资产（记 WARN），不影响其余 server。 */
    public synchronized void refresh() {
        Map<String, ResolvedServer> merged = new LinkedHashMap<>();
        harnessProperties.getMcp().getServers().forEach((name, config) ->
                merged.put(name, new ResolvedServer(name, "yml", null,
                        config.getBaseUrl(), defaultEndpoint(config.getEndpoint()),
                        enabledToolTimeouts(config), harnessProperties.getMcp())));

        int assetCount = 0;
        for (PlatformAsset asset : assetMapper.selectList(new LambdaQueryWrapper<PlatformAsset>()
                .eq(PlatformAsset::getAssetType, ASSET_TYPE))) {
            try {
                JsonNode config = objectMapper.readTree(asset.getConfigJson());
                String baseUrl = config.path("baseUrl").asText("");
                if (!config.path("enabled").asBoolean(true) || !StringUtils.hasText(baseUrl)) {
                    // 显式禁用 = 从运行时视图移除（含同名 yml 项）
                    merged.remove(asset.getName());
                    continue;
                }
                merged.put(asset.getName(), resolveAsset(asset, config, baseUrl));
                assetCount++;
            } catch (Exception ex) {
                log.warn("mcp-servers 资产解析失败（已跳过）: name={}, reason={}",
                        asset.getName(), ex.toString());
            }
        }
        servers.clear();
        servers.putAll(merged);
        log.info("mcp server registry refreshed: {} servers (assets={}, yml={})", merged.size(),
                assetCount, harnessProperties.getMcp().getServers().size());
    }

    /** 只读视图：server 名 → 解析结果（合并完成，含 enabled 剔除）。 */
    public Map<String, ResolvedServer> view() {
        return Collections.unmodifiableMap(servers);
    }

    public Optional<ResolvedServer> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(servers.get(name));
    }

    /** 草稿探测配置：静态 Mcp 配置副本 + 草稿 baseUrl 主机并入白名单（与保存后的运行时授权同语义）。 */
    public AgentHarnessProperties.Mcp probeConfig(String baseUrl) {
        return mcpConfigWithAssetHost(baseUrl);
    }

    private ResolvedServer resolveAsset(PlatformAsset asset, JsonNode config, String baseUrl) {
        String endpoint = defaultEndpoint(config.path("endpoint").asText("/mcp"));
        Map<String, Integer> toolTimeouts = new LinkedHashMap<>();
        JsonNode tools = config.path("tools");
        if (tools.isObject()) {
            tools.fields().forEachRemaining(entry -> {
                int timeout = entry.getValue().path("timeoutMs").asInt(
                        entry.getValue().asInt(0));
                if (timeout > 0) {
                    toolTimeouts.put(entry.getKey(), timeout);
                }
            });
        }
        return new ResolvedServer(asset.getName(), "asset", asset.getId(),
                baseUrl, endpoint, toolTimeouts, mcpConfigWithAssetHost(baseUrl));
    }

    /** 静态 Mcp 配置副本：allowedHosts 并入资产 baseUrl 的主机（其余授权/重试语义不变）。 */
    private AgentHarnessProperties.Mcp mcpConfigWithAssetHost(String baseUrl) {
        AgentHarnessProperties.Mcp copy = new AgentHarnessProperties.Mcp();
        copy.setServers(harnessProperties.getMcp().getServers());
        copy.setAllowedInternalAddresses(harnessProperties.getMcp().getAllowedInternalAddresses());
        copy.setRetryAttempts(harnessProperties.getMcp().getRetryAttempts());
        copy.setRetryBackoffMs(harnessProperties.getMcp().getRetryBackoffMs());
        Set<String> hosts = new LinkedHashSet<>(harnessProperties.getMcp().getAllowedHosts());
        try {
            String host = URI.create(baseUrl).getHost();
            if (StringUtils.hasText(host)) {
                hosts.add(host);
            }
        } catch (Exception ignored) {
            // 非法 URL 留给 SSRF 校验阶段报错
        }
        copy.setAllowedHosts(List.copyOf(hosts));
        return copy;
    }

    private static Map<String, Integer> enabledToolTimeouts(AgentHarnessProperties.McpServer config) {
        Map<String, Integer> timeouts = new LinkedHashMap<>();
        config.getTools().forEach((name, tool) -> {
            if (tool.isEnabled() && tool.getTimeoutMs() > 0) {
                timeouts.put(name, tool.getTimeoutMs());
            }
        });
        return timeouts;
    }

    private static String defaultEndpoint(String endpoint) {
        return StringUtils.hasText(endpoint) ? endpoint : "/mcp";
    }
}
