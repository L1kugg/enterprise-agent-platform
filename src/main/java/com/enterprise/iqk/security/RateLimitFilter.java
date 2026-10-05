package com.enterprise.iqk.security;

import com.enterprise.iqk.config.properties.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimitProperties rateLimitProperties;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    // 进程内 bucket 映射的硬上限，防止大量不同的 key 突发涌入
    // 拖垮 JVM。一旦超限就清空全部 bucket；正常调用者会在
    // 下一次请求时重建自己的 bucket。
    private static final int MAX_BUCKETS = 50_000;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!rateLimitProperties.isEnabled() || request.getRequestURI().startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }
        String key = resolveKey(request);
        if (buckets.size() >= MAX_BUCKETS) {
            // 内存安全保护：当映射已满（很可能是不同 key 的洪峰）时，
            // 先清空全部条目再添加新条目。
            buckets.clear();
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket());
        if (!bucket.tryConsume(1)) {
            response.setStatus(429);
            // 手写 JSON 必须显式设 UTF-8：Servlet 默认 ISO-8859-1，中文会整个变成问号
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json");
            response.getWriter().write("{\"ok\":0,\"msg\":\"请求太频繁了，请稍后再试\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String resolveKey(HttpServletRequest request) {
        String tenantId = resolveTenant(request);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && StringUtils.hasText(authentication.getName())) {
            return "tenant:" + tenantId + ":principal:" + authentication.getName();
        }
        return "tenant:" + tenantId + ":ip:" + resolveClientIp(request);
    }

    /**
     * 为限流 key 选取最有效的客户端 IP。当请求经过反向代理时，直连的
     * {@code RemoteAddr} 是代理自身的地址，所有匿名调用者会共享同一个
     * bucket，单个攻击者即可耗尽整个租户的配额。仅当直连对端是
     * 私有/回环地址（即我们位于代理之后）时才解析 {@code X-Forwarded-For}，
     * 并优先选取最右侧的非私有地址，防止恶意调用者伪造最左侧条目
     * 来轮换自己的 bucket。
     */
    static String resolveClientIp(HttpServletRequest request) {
        String direct = StringUtils.hasText(request.getRemoteAddr()) ? request.getRemoteAddr() : "unknown";
        String forwarded = request.getHeader("X-Forwarded-For");
        if (!StringUtils.hasText(forwarded)) {
            return direct;
        }
        if (!isPrivateOrLoopback(direct)) {
            // 直连对端已经是公网地址，此时 X-Forwarded-For 头不可信，
            // 继续使用直连地址。
            return direct;
        }
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (StringUtils.hasText(hop) && !isPrivateOrLoopback(hop)) {
                return hop;
            }
        }
        return direct;
    }

    private static boolean isPrivateOrLoopback(String ip) {
        if (ip == null) {
            return false;
        }
        if ("127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
            return true;
        }
        // 简单的文本前缀检查即可覆盖常见的 RFC1918 / 链路本地地址段。
        // 这里刻意避免使用 InetAddress 解析，因为 X-Forwarded-For 是
        // 字符串，且我们希望失败即关闭（把无法解析的值视为非私有地址，
        // 从而继续使用直连地址）。
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) {
            return true;
        }
        if (ip.startsWith("169.254.")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            int firstDot = ip.indexOf('.', 4);
            if (firstDot > 0) {
                try {
                    int second = Integer.parseInt(ip.substring(4, firstDot));
                    if (second >= 16 && second <= 31) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                    // 继续向下执行
                }
            }
        }
        return false;
    }

    private String resolveTenant(HttpServletRequest request) {
        Object tenantFromAttr = request.getAttribute(TenantContext.TENANT_REQUEST_ATTRIBUTE);
        if (tenantFromAttr != null && StringUtils.hasText(String.valueOf(tenantFromAttr))) {
            return TenantContext.normalize(String.valueOf(tenantFromAttr));
        }
        return TenantContext.normalize(request.getHeader(TenantContext.TENANT_HEADER));
    }

    private Bucket newBucket() {
        Refill refill = Refill.greedy(rateLimitProperties.getRefillTokens(),
                Duration.ofSeconds(rateLimitProperties.getRefillSeconds()));
        Bandwidth limit = Bandwidth.classic(rateLimitProperties.getCapacity(), refill);
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * 丢弃已完全补满令牌的 bucket（至少空闲了一个补充窗口），避免
     * 按租户/按 IP 的 bucket 映射无限增长。基于调度器的淘汰是安全的：
     * 被移除的 key 只是从一个全新的 bucket 重新开始。
     */
    @Scheduled(fixedDelayString = "${app.rate-limit.evict-interval-ms:300000}")
    public void evictIdleBuckets() {
        if (!rateLimitProperties.isEnabled()) {
            return;
        }
        if (buckets.isEmpty()) {
            return;
        }
        long capacity = rateLimitProperties.getCapacity();
        buckets.entrySet().removeIf(entry -> entry.getValue().getAvailableTokens() >= capacity);
    }
}
