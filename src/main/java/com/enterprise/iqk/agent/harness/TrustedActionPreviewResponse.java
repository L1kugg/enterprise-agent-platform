package com.enterprise.iqk.agent.harness;

import java.time.Instant;
import java.util.Map;

/** 受信动作预览响应：返回确认 token 与脱敏后的动作预览，供人工/上游审阅后决定是否执行。 */
public record TrustedActionPreviewResponse(
        /** 是否受理（1=已生成 token） */
        int ok,
        /** 确认 token（ta-{uuid}），一次性消费，10 分钟过期 */
        String token,
        /** 待执行的动作名 */
        String action,
        /** token 过期时间 */
        Instant expiresAt,
        /** 脱敏预览：apply_patch 类动作会附带统一 diff，其余为待确认输入摘要 */
        Map<String, Object> preview
) {
}
