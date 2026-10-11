package com.enterprise.iqk.service.agent;

import java.util.List;

/** 会话前安全拦截结果：checked=至少评估过一个启用中的防护；首个命中即短路。 */
public record SafetyMatchResult(
        boolean checked,
        boolean blocked,
        Long hitGuardId,
        String hitGuardName,
        String hitLevel,
        List<String> matchedFragments,
        String blockMessage
) {

    private static final SafetyMatchResult NOT_BLOCKED =
            new SafetyMatchResult(false, false, null, null, "NONE", List.of(), "");

    public static SafetyMatchResult notBlocked() {
        return NOT_BLOCKED;
    }
}
