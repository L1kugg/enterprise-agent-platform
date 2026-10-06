package com.enterprise.iqk.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
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
    private DatabaseQuery databaseQuery = new DatabaseQuery();
    private TrustedAction trustedAction = new TrustedAction();

    @Data
    public static class Workspace {
        private String root = ".";
        private boolean writeEnabled = false;
        private boolean shellEnabled = false;
        private int commandTimeoutSeconds = 10;
        private int maxCommandOutputBytes = 12_000;
        private int maxFileBytes = 20_000;
        private int maxSearchFiles = 1_000;
        /**
         * 默认不含 mvn：mvn test 会解析 POM 并可能联网拉依赖，属于
         * 网络可达命令；确需启用时由运维显式加入，并建议保持 mvnOffline=true。
         */
        private Set<String> allowedCommands = new LinkedHashSet<>(Set.of("pwd", "ls", "rg", "git"));
        /** mvn 强制离线（注入 -o），阻止测试进程联网拉取依赖；确需在线构建时置 false */
        private boolean mvnOffline = true;
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
        // 瞬时故障重试次数：网络异常（超时/连接失败）与 5xx 在适配器层
        // 重试消化，不作为失败观测喂给模型——重试发生在 HTTP 层，
        // 不消耗模型 token。0 表示关闭重试。
        private int retryAttempts = 2;
        // 重试基础退避间隔（毫秒），按重试序号线性递增：第 1 次重试睡 1 倍、第 2 次睡 2 倍。
        private long retryBackoffMs = 200;
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

    /**
     * 主库只读查询动作（query_database）的防护预算。
     * maxRows 必须不大于 HarnessPayloadSanitizer 的集合截断上限（30），
     * 否则行数据会在消毒器层被二次截断并打标，与 payload 自己的 rowCount 自相矛盾。
     */
    @Data
    public static class DatabaseQuery {
        private boolean enabled = true;
        private int maxRows = 30;
        private int queryTimeoutSeconds = 5;
        private int maxCellChars = 200;
        private int maxSqlLength = 4_000;
        /** 拒绝查询的表/schema 黑名单：凭证类默认全拉黑（观测不做键名脱敏，一旦放行原样进模型与轨迹） */
        private Set<String> deniedTables = new LinkedHashSet<>(Set.of(
                "users", "roles", "user_roles", "refresh_tokens", "api_keys", "mysql"));
    }

    /**
     * 受信动作两段式确认的挂起存储与 token 有效期。
     * store=memory 仅适合单实例；多实例部署必须切 redis，
     * 否则 preview 与 execute 落在不同节点时 token 互相不可见。
     */
    @Data
    public static class TrustedAction {
        /** memory | redis */
        private String store = "memory";
        private Duration tokenTtl = Duration.ofMinutes(10);
    }
}
