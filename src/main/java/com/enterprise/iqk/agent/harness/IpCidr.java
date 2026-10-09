package com.enterprise.iqk.agent.harness;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * IPv4/IPv6 CIDR 网段匹配工具（MCP 内部地址授权专用）。
 * 仅支持 IP 字面量网段（如 "127.0.0.1/32"、"::1/128"、"172.16.0.0/12"），
 * 缺省前缀长度视为全位（/32 或 /128）。
 * 不用 hutool 的 Ipv4Util：其只覆盖 IPv4，而回环授权需要 ::1/128。
 */
final class IpCidr {

    private IpCidr() {
    }

    /** 判断 addr 是否落在 cidr 网段内；cidr 非法或 v4/v6 家族不匹配返回 false（fail-closed）。 */
    static boolean contains(InetAddress addr, String cidr) {
        if (addr == null || cidr == null || cidr.isBlank()) {
            return false;
        }
        String[] parts = cidr.split("/", 2);
        byte[] base;
        try {
            base = InetAddress.getByName(parts[0].trim()).getAddress();
        } catch (UnknownHostException ex) {
            // 网段必须写 IP 字面量；主机名形式无法静态判定，直接拒绝
            return false;
        }
        byte[] target = addr.getAddress();
        if (base.length != target.length) {
            return false; // IPv4 与 IPv6 家族不匹配
        }
        int prefixBits = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : base.length * 8;
        if (prefixBits < 0 || prefixBits > base.length * 8) {
            return false;
        }
        int fullBytes = prefixBits / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (base[i] != target[i]) {
                return false;
            }
        }
        int remainderBits = prefixBits % 8;
        if (remainderBits == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remainderBits)) & 0xFF;
        return (base[fullBytes] & mask) == (target[fullBytes] & mask);
    }
}
