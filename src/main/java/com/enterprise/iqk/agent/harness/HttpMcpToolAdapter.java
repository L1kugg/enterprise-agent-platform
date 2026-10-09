package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * HTTP MCP 工具适配器：用 Apache HttpClient 5 直连外部 MCP server 发 JSON-RPC 2.0
 * tools/call（自研桥接，非 MCP SDK）。server/tool 的启用状态、baseUrl、路径与
 * 超时全部来自 app.agent-harness.mcp 配置，supports() 五重校验通过才放行调用。
 * 安全线：仅 http(s)；allowed-hosts 白名单只收窄主机范围、不豁免地址检查；
 * 回环/私有/链路本地（含云元数据）等危险地址必须经 allowed-internal-addresses
 * 按"主机+端口+网段"逐条显式授权；权威校验在连接点的 DNS 解析器内完成——
 * 解析结果直接交给 socket，不存在"校验后由客户端重新解析"的重绑定窗口；
 * 重定向显式禁用。另有 2 MiB 响应上限（流式有界读取）。
 * 韧性线：瞬时故障（网络异常/5xx）在本层带退避重试消化，重试不经过模型、
 * 不消耗 token，重试次数与退避间隔来自 app.agent-harness.mcp 配置
 * （HC5 内建重试已关闭，重试语义由本层统一负责）。
 */
@Slf4j
@Component
public class HttpMcpToolAdapter implements McpToolAdapter {
    // 出站 MCP 调用仅允许公共 HTTP(S) 端点。任何
    // RFC1918 / 回环 / 链路本地 / 云元数据地址一律拒绝，
    // 防止 Agent 调用 mcp_call 时把请求变成针对内部服务
    // 或宿主机元数据 API 的 SSRF 探测；确需访问的内部服务
    // 必须在 allowed-internal-addresses 中逐条显式授权。
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final AgentHarnessProperties harnessProperties;
    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;

    // 限制 MCP HTTP 响应体大小，防止恶意或被攻陷的 MCP
    // 服务器返回数 MB 载荷耗尽 JVM 内存。2 MiB 与 workspace
    // 的 max-file-bytes 上限同一数量级，对典型的 JSON-RPC
    // 响应而言已足够宽裕。
    private static final long MAX_MCP_RESPONSE_BYTES = 2L * 1024L * 1024L;

    // 一次出站 MCP 调用的校验上下文：execute/listTools 发送前置入，
    // 连接点的 PinningDnsResolver 据此对解析结果做授权校验；
    // 阻塞调用（同线程同时只有一次发送），ThreadLocal 即可安全隔离。
    private record CallContext(String host, int effectivePort) {
    }

    private static final ThreadLocal<CallContext> CALL_CONTEXT = new ThreadLocal<>();

    /** DNS 解析命中未授权危险地址：确定性策略违规，以非 IOException 抛出，避免进入瞬时故障重试路径。 */
    private static final class UnauthorizedAddressException extends RuntimeException {
        private UnauthorizedAddressException(String host) {
            super("host " + host + " resolved to a non-authorized address; "
                    + "blocked by SSRF policy (no matching allowed-internal-addresses grant)");
        }
    }

    /** 响应体超限：确定性失败，execute 层转 status=error 的 Map。 */
    private static final class ResponseTooLargeException extends IOException {
        private ResponseTooLargeException(long maxBytes) {
            super("mcp response exceeds " + maxBytes + " bytes");
        }
    }

    public HttpMcpToolAdapter(AgentHarnessProperties harnessProperties, ObjectMapper objectMapper) {
        this.harnessProperties = harnessProperties;
        this.objectMapper = objectMapper;
        // 连接点 DNS 固定：连接管理器解析出的地址即实际连接地址，
        // 不再交给底层做第二次解析，消除"校验时是公共 IP、连接时被改判"的重绑定窗口。
        // 连接管理器随 httpClient.close() 一并关闭，不单独持有。
        this.httpClient = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(new PinningDnsResolver())
                        .build())
                // 关闭 HC5 内建重试（重试次数置 0）：瞬时故障重试统一由 sendWithRetry 按配置计数与退避
                .setRetryStrategy(new DefaultHttpRequestRetryStrategy(0, TimeValue.ZERO_MILLISECONDS))
                // 重定向显式禁用：跟随跳转会让请求脱离已校验的 host/地址；3xx 按非 2xx 报错处理。
                // 若未来要支持自动跟随，必须对每一跳重新执行完整 SSRF 校验（含 DNS 固定与授权）。
                .setDefaultRequestConfig(RequestConfig.custom().setRedirectsEnabled(false).build())
                .build();
    }

    @PreDestroy
    void shutdown() {
        try {
            httpClient.close();
        } catch (IOException ex) {
            log.warn("mcp http client close failed: {}", ex.getMessage());
        }
    }

    @Override
    public String server() {
        return "configured-http";
    }

    @Override
    public String tool() {
        return "configured-http";
    }

    /** 五重校验：配置存在、server 启用、baseUrl 非空且安全（SSRF 预检）、tool 配置存在、tool 启用 */
    @Override
    public boolean supports(String server, String tool) {
        AgentHarnessProperties.McpServer serverConfig = harnessProperties.getMcp().getServers().get(server);
        return serverConfig != null
                && serverConfig.isEnabled()
                && StringUtils.hasText(serverConfig.getBaseUrl())
                && isSafeBaseUrl(serverConfig.getBaseUrl(), harnessProperties.getMcp())
                && serverConfig.getTools().containsKey(tool)
                && serverConfig.getTools().get(tool).isEnabled();
    }

    /** 发 JSON-RPC tools/call：先 SSRF 预检 baseUrl（连接点还有权威校验），再按 tool 超时设置请求（瞬时故障先本层重试）；非 2xx、超限、异常一律转 status=error 的 Map，不抛异常 */
    @Override
    public Object execute(String server, String tool, Map<String, Object> arguments) {
        try {
            AgentHarnessProperties.McpServer serverConfig = harnessProperties.getMcp().getServers().get(server);
            AgentHarnessProperties.McpTool toolConfig = serverConfig.getTools().get(tool);
            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", UUID.randomUUID().toString(),
                    "method", "tools/call",
                    "params", Map.of("name", tool, "arguments", arguments)
            ));
            URI uri = resolveUri(serverConfig.getBaseUrl(), toolConfig.getPath());
            ClassicHttpRequest request = ClassicRequestBuilder.post(uri)
                    .setEntity(new StringEntity(body, ContentType.APPLICATION_JSON))
                    .build();
            CALL_CONTEXT.set(new CallContext(uri.getHost(), effectivePort(uri)));
            try (CloseableHttpResponse response = sendWithRetry(request, toolConfig.getTimeoutMs())) {
                byte[] bodyBytes = readBounded(response.getEntity(), MAX_MCP_RESPONSE_BYTES);
                if (response.getCode() < 200 || response.getCode() >= 300) {
                    return Map.of("status", "error", "message", "mcp http status: " + response.getCode());
                }
                return objectMapper.readValue(new String(bodyBytes, StandardCharsets.UTF_8), Object.class);
            } catch (ResponseTooLargeException ex) {
                return Map.of("status", "error", "message", ex.getMessage());
            } finally {
                CALL_CONTEXT.remove();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); // 恢复中断标记，交由上层决定收尾方式
            return Map.of("status", "error", "message", "mcp http call interrupted");
        } catch (Exception ex) {
            return Map.of("status", "error", "message", "mcp http call failed: " + ex.getMessage());
        }
    }

    /**
     * 发送请求并重试瞬时故障：网络 IOException（超时/连接失败）与 5xx 视为可重试，
     * 4xx 与其余异常原样失败——前者是确定性错误，重试无意义。退避按重试序号
     * 线性递增（第 1 次睡 1 倍、第 2 次睡 2 倍）。重试全部发生在本层，
     * 成功后模型无感知，不消耗任何模型 token。
     */
    private CloseableHttpResponse sendWithRetry(ClassicHttpRequest request, int timeoutMs)
            throws IOException, HttpException, InterruptedException {
        int retries = Math.max(0, harnessProperties.getMcp().getRetryAttempts());
        long backoffMs = Math.max(0, harnessProperties.getMcp().getRetryBackoffMs());
        for (int attempt = 0; ; attempt++) {
            if (attempt > 0) {
                Thread.sleep(backoffMs * attempt);
            }
            CloseableHttpResponse response;
            try {
                response = httpClient.execute(request, requestContext(timeoutMs));
            } catch (IOException ex) {
                if (attempt >= retries) {
                    throw ex;
                }
                continue;
            }
            if (!isRetryableStatus(response.getCode()) || attempt >= retries) {
                return response;
            }
            response.close(); // 丢弃可重试的 5xx 响应，连接归还连接池后再退避重试
        }
    }

    /** 每请求配置：connect/response 超时 + 重定向禁用（覆盖连接级默认，双保险） */
    private static HttpClientContext requestContext(int timeoutMs) {
        HttpClientContext context = HttpClientContext.create();
        context.setRequestConfig(RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(timeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(timeoutMs))
                .setRedirectsEnabled(false)
                .build());
        return context;
    }

    /** 有界读取响应体：超过 maxBytes 立即中断读取（避免超大响应先全量进内存再被拒） */
    private static byte[] readBounded(HttpEntity entity, long maxBytes) throws IOException {
        if (entity == null) {
            return new byte[0];
        }
        try (InputStream in = entity.getContent()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8 * 1024);
            byte[] buffer = new byte[8 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new ResponseTooLargeException(maxBytes);
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** 5xx 是服务端瞬时故障可重试；4xx 是确定性错误（参数/权限），重试无意义 */
    private static boolean isRetryableStatus(int statusCode) {
        return statusCode >= 500 && statusCode < 600;
    }

    /** 不带 server/tool 的调用直接拒绝（本适配器必须显式指定目标） */
    @Override
    public Object execute(Map<String, Object> arguments) {
        return Map.of("status", "error", "message", "configured MCP call requires server and tool");
    }

    /**
     * 动态发现指定 server 的工具列表（JSON-RPC tools/list）。
     * 返回格式：[{"name":"get_weather","description":"查询天气","inputSchema":{...}}]
     * server 不存在或上游不可达时返回空列表（启动阶段不因单个 server 故障阻断）。
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listTools(String server) {
        AgentHarnessProperties.McpServer serverConfig = harnessProperties.getMcp().getServers().get(server);
        if (serverConfig == null || !serverConfig.isEnabled() || !StringUtils.hasText(serverConfig.getBaseUrl())) {
            return List.of();
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", UUID.randomUUID().toString(),
                    "method", "tools/list"
            ));
            URI uri = resolveUri(serverConfig.getBaseUrl(), "/mcp/tools/list");
            ClassicHttpRequest request = ClassicRequestBuilder.post(uri)
                    .setEntity(new StringEntity(body, ContentType.APPLICATION_JSON))
                    .build();
            CALL_CONTEXT.set(new CallContext(uri.getHost(), effectivePort(uri)));
            try (CloseableHttpResponse response = sendWithRetry(request, 5_000)) {
                if (response.getCode() < 200 || response.getCode() >= 300) {
                    log.warn("MCP tools/list failed: server={}, status={}", server, response.getCode());
                    return List.of();
                }
                byte[] bodyBytes = readBounded(response.getEntity(), MAX_MCP_RESPONSE_BYTES);
                Map<String, Object> parsed = objectMapper.readValue(
                        new String(bodyBytes, StandardCharsets.UTF_8), Map.class);
                Object result = parsed.get("result");
                if (!(result instanceof Map<?, ?> resultMap)) {
                    return List.of();
                }
                Object tools = resultMap.get("tools");
                if (!(tools instanceof List<?> toolList)) {
                    return List.of();
                }
                return toolList.stream()
                        .filter(t -> t instanceof Map)
                        .map(t -> (Map<String, Object>) t)
                        .toList();
            } finally {
                CALL_CONTEXT.remove();
            }
        } catch (Exception ex) {
            log.warn("MCP tools/list error: server={}, reason={}", server, ex.getMessage());
            return List.of();
        }
    }

    /** 拼 baseUrl + tool path 成最终 URI（拼接前再过一次 SSRF 预检，防配置运行期被改；连接点另有权威校验） */
    private URI resolveUri(String baseUrl, String path) {
        if (!isSafeBaseUrl(baseUrl, harnessProperties.getMcp())) {
            throw new IllegalArgumentException("MCP baseUrl is not a permitted public endpoint: " + baseUrl);
        }
        String safeBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String safePath = path.startsWith("/") ? path : "/" + path;
        return URI.create(safeBase + safePath);
    }

    /**
     * SSRF 预检（fail-fast 用途；连接点的 {@link PinningDnsResolver} 校验才是权威）：
     * 拒绝 scheme 不是 http(s)、host 不合规、或解析结果含未授权危险地址的 baseUrl。
     * 白名单 {@code allowedHosts} 只负责收窄可访问的主机范围，命中不豁免任何地址检查；
     * 回环/私有/链路本地（含云元数据）地址必须经 {@code allowedInternalAddresses}
     * 按"主机+端口+网段"显式授权。若不做此校验，运维人员（或由 LLM 驱动的
     * Agent 调用 mcp_call）可能把 MCP HTTP 适配器指向诸如
     * http://169.254.169.254/latest/meta-data/ 的地址，以窃取云实例凭据。
     *
     * <p>开发环境指向 localhost mock 的授权示例：
     * {@code allowed-internal-addresses: [{host: "localhost", cidrs: ["127.0.0.1/32", "::1/128"]}]}。
     */
    static boolean isSafeBaseUrl(String baseUrl, AgentHarnessProperties.Mcp mcpConfig) {
        if (!StringUtils.hasText(baseUrl) || mcpConfig == null) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            return false;
        }
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            return false;
        }
        if (!hostMatchesAllowList(host, mcpConfig.getAllowedHosts())) {
            return false;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            return addressesAuthorized(host, effectivePort(uri), addresses,
                    mcpConfig.getAllowedInternalAddresses());
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    /**
     * 地址级授权校验：公共地址直接放行；任何危险地址（回环/任意本地/链路本地/
     * 站点本地/多播）都必须命中一条授权（host 匹配 + 端口匹配 + 地址落在授权网段），
     * 否则整体拒绝（fail-closed，与"任一解析地址危险即拒绝"的原语义一致）。
     */
    static boolean addressesAuthorized(String host, int effectivePort, InetAddress[] addresses,
                                       List<AgentHarnessProperties.AddressGrant> grants) {
        List<AgentHarnessProperties.AddressGrant> safeGrants = grants == null ? List.of() : grants;
        for (InetAddress addr : addresses) {
            if (!isDangerousAddress(addr)) {
                continue;
            }
            if (!hasMatchingGrant(host, effectivePort, addr, safeGrants)) {
                return false;
            }
        }
        return true;
    }

    /** 是否存在覆盖该危险地址的显式授权：host 匹配 +（如声明了端口）端口相等 + 地址落在某授权网段内 */
    private static boolean hasMatchingGrant(String host, int effectivePort, InetAddress addr,
                                            List<AgentHarnessProperties.AddressGrant> grants) {
        for (AgentHarnessProperties.AddressGrant grant : grants) {
            if (grant == null || !hostMatchesAllowList(host, List.of(grant.getHost()))) {
                continue;
            }
            if (grant.getPort() != null && grant.getPort() != effectivePort) {
                continue;
            }
            for (String cidr : grant.getCidrs()) {
                if (IpCidr.contains(addr, cidr)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 危险地址分类：回环 / 任意本地(0.0.0.0) / 链路本地(含 169.254.169.254 云元数据) / 站点本地(RFC1918) / 多播 */
    private static boolean isDangerousAddress(InetAddress addr) {
        return addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isMulticastAddress();
    }

    /** URI 端口归一化：未显式写端口时按 scheme 补默认值，参与授权端口匹配 */
    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * 连接点 DNS 解析器（权威校验 + 固定）：解析结果先过授权校验再交给连接管理器，
     * socket 连的就是已校验地址；每次新建连接（含每轮重试）都会重新解析重新校验。
     * 缺少调用上下文（理论上的防御分支）时不做端口匹配、仍按公共地址放行——
     * 收紧程度不低于预检。
     */
    private class PinningDnsResolver implements DnsResolver {

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] addresses = SystemDefaultDnsResolver.INSTANCE.resolve(host);
            CallContext ctx = CALL_CONTEXT.get();
            if (!addressesAuthorized(host, ctx == null ? -1 : ctx.effectivePort(), addresses,
                    harnessProperties.getMcp().getAllowedInternalAddresses())) {
                throw new UnauthorizedAddressException(host);
            }
            return addresses;
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            return SystemDefaultDnsResolver.INSTANCE.resolveCanonicalHostname(host);
        }
    }

    /** 主机名白名单匹配：".example.com" 后缀匹配或精确主机名（大小写不敏感） */
    private static boolean hostMatchesAllowList(String host, List<String> allowedHosts) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return false;
        }
        String normalized = host.toLowerCase();
        for (String pattern : allowedHosts) {
            if (!StringUtils.hasText(pattern)) {
                continue;
            }
            String p = pattern.trim().toLowerCase();
            if (p.startsWith(".")) {
                // 后缀匹配：".internal.example.com" 可匹配 "a.internal.example.com"
                if (normalized.endsWith(p)) {
                    return true;
                }
            } else if (p.equals(normalized)) {
                return true;
            }
        }
        return false;
    }
}
