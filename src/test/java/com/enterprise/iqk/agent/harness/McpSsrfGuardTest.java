package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSRF 校验核心直测（自原 HttpMcpToolAdapterTest 迁移，断言不变）：
 * 白名单不豁免地址检查、内部地址需"主机+端口+网段"显式授权、
 * fail-closed 语义与云元数据封堵在这里守住——这是修过 P0 换来的回归面。
 */
class McpSsrfGuardTest {

    @Test
    void allowlistedHostWithoutGrantCannotReachLocalAddress() {
        // P0 回归：白名单命中但解析到回环地址且无显式授权 —— 旧实现命中白名单即放行
        AgentHarnessProperties.Mcp mcp = new AgentHarnessProperties.Mcp();
        mcp.setAllowedHosts(java.util.List.of("localhost"));
        assertThat(McpSsrfGuard.isSafeBaseUrl("http://localhost:8080/mcp", mcp)).isFalse();
    }

    @Test
    void internalAddressAccessibleOnlyWithMatchingGrant() {
        AgentHarnessProperties.Mcp mcp = new AgentHarnessProperties.Mcp();
        mcp.setAllowedHosts(java.util.List.of("localhost"));
        mcp.setAllowedInternalAddresses(
                java.util.List.of(grant("localhost", null, "127.0.0.1/32", "::1/128")));
        assertThat(McpSsrfGuard.isSafeBaseUrl("http://localhost:8080/mcp", mcp)).isTrue();

        // 授权声明了端口而实际端口不符：不放行
        mcp.setAllowedInternalAddresses(java.util.List.of(grant("localhost", 99, "127.0.0.1/32", "::1/128")));
        assertThat(McpSsrfGuard.isSafeBaseUrl("http://localhost:8080/mcp", mcp)).isFalse();
    }

    @Test
    void grantsAreScopedToTheirDeclaredHost() throws Exception {
        // 授权只对声明的主机生效，不能被其它白名单主机借用
        assertThat(McpSsrfGuard.addressesAuthorized("other-host", 8080,
                new java.net.InetAddress[]{java.net.InetAddress.getByName("127.0.0.1")},
                java.util.List.of(grant("localhost", null, "127.0.0.1/32")))).isFalse();
    }

    @Test
    void grantsCoverComposeServiceNetwork() throws Exception {
        // 模拟 compose 服务名解析到 Docker 默认容器网段（172.16.0.0/12）
        AgentHarnessProperties.AddressGrant g = grant("mcp-server", 9101, "172.16.0.0/12");
        java.net.InetAddress inRange = java.net.InetAddress.getByName("172.18.0.5");
        // 192.168.x 仍是私有地址（需要授权）但在授权网段之外
        java.net.InetAddress outOfRange = java.net.InetAddress.getByName("192.168.1.5");

        assertThat(McpSsrfGuard.addressesAuthorized("mcp-server", 9101,
                new java.net.InetAddress[]{inRange}, java.util.List.of(g))).isTrue();
        // 端口/主机/网段任一不符即拒绝
        assertThat(McpSsrfGuard.addressesAuthorized("mcp-server", 9102,
                new java.net.InetAddress[]{inRange}, java.util.List.of(g))).isFalse();
        assertThat(McpSsrfGuard.addressesAuthorized("other-host", 9101,
                new java.net.InetAddress[]{inRange}, java.util.List.of(g))).isFalse();
        assertThat(McpSsrfGuard.addressesAuthorized("mcp-server", 9101,
                new java.net.InetAddress[]{outOfRange}, java.util.List.of(g))).isFalse();
    }

    @Test
    void publicHostNeedsNoGrant() throws Exception {
        // 公共地址无需授权即可访问（原有默认行为保留）
        assertThat(McpSsrfGuard.addressesAuthorized("example.com", 443,
                new java.net.InetAddress[]{java.net.InetAddress.getByName("8.8.8.8")},
                java.util.List.of())).isTrue();
    }

    @Test
    void rejectsUnreachableOrIllegalEndpoints() throws Exception {
        AgentHarnessProperties.Mcp mcp = new AgentHarnessProperties.Mcp();
        assertThat(McpSsrfGuard.isSafeBaseUrl("ftp://example.com", mcp)).isFalse(); // 非 http(s) scheme
        assertThat(McpSsrfGuard.isSafeBaseUrl("http:///path", mcp)).isFalse(); // 空 host
        assertThat(McpSsrfGuard.isSafeBaseUrl("", mcp)).isFalse(); // 空 baseUrl
        assertThat(McpSsrfGuard.isSafeBaseUrl("http://nonexistent.invalid", mcp)).isFalse(); // 域名无法解析
        // 云元数据地址字面量（链路本地）无授权即拒绝
        assertThat(McpSsrfGuard.isSafeBaseUrl("http://169.254.169.254/latest/meta-data/", mcp)).isFalse();
    }

    /** 授权项构造辅助 */
    private static AgentHarnessProperties.AddressGrant grant(String host, Integer port, String... cidrs) {
        AgentHarnessProperties.AddressGrant g = new AgentHarnessProperties.AddressGrant();
        g.setHost(host);
        g.setPort(port);
        g.setCidrs(java.util.List.of(cidrs));
        return g;
    }
}
