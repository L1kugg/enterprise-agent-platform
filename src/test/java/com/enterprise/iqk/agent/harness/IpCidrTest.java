package com.enterprise.iqk.agent.harness;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

class IpCidrTest {

    @Test
    void matchesIpv4PrefixIncludingRemainderBits() throws Exception {
        // /12 覆盖 172.16.0.0 - 172.31.255.255，检验整字节 + 余位掩码两条路径
        assertThat(IpCidr.contains(addr("172.16.0.0"), "172.16.0.0/12")).isTrue();
        assertThat(IpCidr.contains(addr("172.31.255.255"), "172.16.0.0/12")).isTrue();
        assertThat(IpCidr.contains(addr("172.32.0.0"), "172.16.0.0/12")).isFalse();
        assertThat(IpCidr.contains(addr("172.15.255.255"), "172.16.0.0/12")).isFalse();
        assertThat(IpCidr.contains(addr("10.0.0.1"), "172.16.0.0/12")).isFalse();
    }

    @Test
    void matchesExactIpv4Host() throws Exception {
        assertThat(IpCidr.contains(addr("127.0.0.1"), "127.0.0.1/32")).isTrue();
        assertThat(IpCidr.contains(addr("127.0.0.2"), "127.0.0.1/32")).isFalse();
    }

    @Test
    void matchesIpv6Prefix() throws Exception {
        assertThat(IpCidr.contains(addr("::1"), "::1/128")).isTrue();
        assertThat(IpCidr.contains(addr("::2"), "::1/128")).isFalse();
        assertThat(IpCidr.contains(addr("2001:db8::1"), "2001:db8::/32")).isTrue();
        assertThat(IpCidr.contains(addr("2001:db9::1"), "2001:db8::/32")).isFalse();
    }

    @Test
    void familyMismatchFailsClosed() throws Exception {
        assertThat(IpCidr.contains(addr("::1"), "127.0.0.1/32")).isFalse();
        assertThat(IpCidr.contains(addr("127.0.0.1"), "::1/128")).isFalse();
    }

    @Test
    void missingPrefixDefaultsToFullBits() throws Exception {
        assertThat(IpCidr.contains(addr("127.0.0.1"), "127.0.0.1")).isTrue();
        assertThat(IpCidr.contains(addr("127.0.0.2"), "127.0.0.1")).isFalse();
    }

    @Test
    void invalidCidrFailsClosed() throws Exception {
        assertThat(IpCidr.contains(addr("127.0.0.1"), "not-an-ip/8")).isFalse();
        assertThat(IpCidr.contains(addr("127.0.0.1"), "300.1.1.1/8")).isFalse();
        assertThat(IpCidr.contains(addr("127.0.0.1"), "")).isFalse();
        assertThat(IpCidr.contains(addr("127.0.0.1"), "127.0.0.1/33")).isFalse();
        assertThat(IpCidr.contains(addr("127.0.0.1"), "127.0.0.1/-1")).isFalse();
        assertThat(IpCidr.contains(null, "127.0.0.1/32")).isFalse();
    }

    private static InetAddress addr(String ip) throws Exception {
        return InetAddress.getByName(ip);
    }
}
