package com.enterprise.iqk.security;

import org.slf4j.MDC;
import org.springframework.util.StringUtils;

/**
 * 租户上下文工具：tenant_id 是贯穿检索、记忆与队列任务认领的隔离键。
 * 请求线程的租户由认证过滤器写入 MDC（键为 tenant_id），
 * 后台线程无 MDC 时需显式传参；空白租户统一归一为 public。
 */
public final class TenantContext {
    /** 携带租户 ID 的 HTTP 请求头 */
    public static final String TENANT_HEADER = "X-Tenant-Id";
    /** MDC 中存放租户 ID 的键 */
    public static final String TENANT_REQUEST_ATTRIBUTE = "tenant_id";
    /** 空白租户回落到的默认租户 */
    public static final String DEFAULT_TENANT = "public";

    private TenantContext() {
    }

    /** 去首尾空白；空白值回落默认租户 public。 */
    public static String normalize(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.trim() : DEFAULT_TENANT;
    }

    /** 读取当前线程（MDC）的租户并归一化。 */
    public static String currentTenantId() {
        return normalize(MDC.get(TENANT_REQUEST_ATTRIBUTE));
    }
}
