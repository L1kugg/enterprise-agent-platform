package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

/**
 * MCP 出站 SSRF 纵深防护（从原 HttpMcpToolAdapter 抽出，协议无关，供标准 MCP 传输层复用）。
 *
 * <p>安全线：仅 http(s)；{@code allowed-hosts} 白名单只收窄主机范围、不豁免地址检查；
 * 回环/私有/链路本地（含云元数据 169.254.169.254）等危险地址必须经
 * {@code allowed-internal-addresses} 按"主机+端口+网段"逐条显式授权；权威校验在
 * 连接点的 {@link PinningDnsResolver} 内完成——解析结果直接交给 socket，不存在
 * "校验后由客户端重新解析"的重绑定窗口。
 *
 * <p>若不做此校验，运维人员（或由 LLM 驱动的 Agent 调用 mcp_call）可能把 MCP 客户端
 * 指向诸如 http://169.254.169.254/latest/meta-data/ 的地址，以窃取云实例凭据。
 */
final class McpSsrfGuard {

    /** 出站 MCP 调用仅允许公共 HTTP(S) 端点；确需访问的内部服务必须在 allowed-internal-addresses 中逐条显式授权。 */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private McpSsrfGuard() {
    }

    /**
     * 一次出站 MCP 调用的校验上下文：发送前置入，连接点的 {@link PinningDnsResolver}
     * 据此对解析结果做授权校验；阻塞调用（同线程同时只有一次发送），ThreadLocal 即可安全隔离。
     */
    record CallContext(String host, int effectivePort) {
    }

    /** 发送方在每次 HTTP 发送前后维护的调用上下文（连接点权威校验的端口来源）。 */
    static final ThreadLocal<CallContext> CALL_CONTEXT = new ThreadLocal<>();

    /** 进入一次发送：置入校验上下文（与 finally 中的 {@link #clearContext()} 成对出现）。 */
    static void enterContext(URI uri) {
        CALL_CONTEXT.set(new CallContext(uri.getHost(), effectivePort(uri)));
    }

    /** 退出一次发送：清除校验上下文。 */
    static void clearContext() {
        CALL_CONTEXT.remove();
    }

    /** DNS 解析命中未授权危险地址：确定性策略违规，以非 IOException 抛出，避免进入瞬时故障重试路径。 */
    static final class UnauthorizedAddressException extends RuntimeException {
        UnauthorizedAddressException(String host) {
            super("host " + host + " resolved to a non-authorized address; "
                    + "blocked by SSRF policy (no matching allowed-internal-addresses grant)");
        }
    }

    /**
     * SSRF 预检（fail-fast 用途；连接点的 {@link PinningDnsResolver} 校验才是权威）：
     * 拒绝 scheme 不是 http(s)、host 不合规、或解析结果含未授权危险地址的 baseUrl。
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
    static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /** 主机名白名单匹配：".example.com" 后缀匹配或精确主机名（大小写不敏感） */
    static boolean hostMatchesAllowList(String host, List<String> allowedHosts) {
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

    /**
     * 连接点 DNS 解析器（权威校验 + 固定）：解析结果先过授权校验再交给连接管理器，
     * socket 连的就是已校验地址；每次新建连接（含每轮重试）都会重新解析重新校验。
     * 缺少调用上下文（理论上的防御分支）时不做端口匹配、仍按公共地址放行——
     * 收紧程度不低于预检。
     */
    static final class PinningDnsResolver implements DnsResolver {

        private final java.util.function.Supplier<List<AgentHarnessProperties.AddressGrant>> grantsSupplier;

        PinningDnsResolver(java.util.function.Supplier<List<AgentHarnessProperties.AddressGrant>> grantsSupplier) {
            this.grantsSupplier = grantsSupplier;
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] addresses = SystemDefaultDnsResolver.INSTANCE.resolve(host);
            CallContext ctx = CALL_CONTEXT.get();
            if (!addressesAuthorized(host, ctx == null ? -1 : ctx.effectivePort(), addresses,
                    grantsSupplier.get())) {
                throw new UnauthorizedAddressException(host);
            }
            return addresses;
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            return SystemDefaultDnsResolver.INSTANCE.resolveCanonicalHostname(host);
        }
    }
}
