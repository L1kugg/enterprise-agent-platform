package com.enterprise.iqk.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Data
@ConfigurationProperties(prefix = "app.agent-harness")
public class AgentHarnessProperties {
    private boolean trustedRuntimeEnabled = true;
    private Set<String> disabledActions = new LinkedHashSet<>();
    private Map<String, Set<String>> tenantAllowedActions = new LinkedHashMap<>();
    private Workspace workspace = new Workspace();
    private Mcp mcp = new Mcp();

    @Data
    public static class Workspace {
        private String root = ".";
        private boolean writeEnabled = false;
        private boolean shellEnabled = false;
        private int commandTimeoutSeconds = 10;
        private int maxCommandOutputBytes = 12_000;
        private int maxFileBytes = 20_000;
        private int maxSearchFiles = 1_000;
        private Set<String> allowedCommands = new LinkedHashSet<>(Set.of("pwd", "ls", "rg", "git", "mvn"));
        private Set<String> allowedGitSubcommands = new LinkedHashSet<>(
                Set.of("status", "diff", "show", "log", "rev-parse", "branch"));
    }

    @Data
    public static class Mcp {
        private Map<String, McpServer> servers = new LinkedHashMap<>();
        // 可选的、由运维人员维护的主机模式列表（精确主机名或类似
        // ".internal.example.com" 的后缀匹配）。即使这些主机解析到
        // 回环/私有地址，也允许调用。
        // 默认为空：所有私有/回环主机一律拒绝。测试和开发环境
        // 若需要指向 localhost mock，可在此设置为
        // ["localhost", "127.0.0.1", "::1"] 之类的值。
        private java.util.List<String> allowedHosts = new java.util.ArrayList<>();
    }

    @Data
    public static class McpServer {
        private boolean enabled = true;
        private String baseUrl = "";
        private Map<String, McpTool> tools = new LinkedHashMap<>();
    }

    @Data
    public static class McpTool {
        private boolean enabled = true;
        private String path = "/mcp/tools/call";
        private int timeoutMs = 5_000;
    }
}
