package com.enterprise.iqk.agent.harness;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * MCP 运行时刷新链：注册表重建 → 客户端缓存全量失效 → 目录重新发现。
 * 平台资产（mcp-servers）增删改后由 PlatformAssetService 钩子调用 refreshAll()，
 * 也可经管理端点手动触发（如 server 端重启后的强制重发现）。
 * 三步各自 best-effort——前一步失败不阻断后一步，失败痕迹在各自日志里。
 */
@Component
@RequiredArgsConstructor
public class McpRuntimeRefresher {

    private final McpServerRegistry serverRegistry;
    private final StandardMcpToolAdapter toolAdapter;
    private final McpToolCatalog toolCatalog;

    public synchronized void refreshAll() {
        serverRegistry.refresh();
        toolAdapter.invalidateAll();
        toolCatalog.refresh();
    }
}
