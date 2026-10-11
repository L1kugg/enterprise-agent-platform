package com.enterprise.iqk.service.agent;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 安全防护匹配核心：KEYWORD 大小写不敏感包含，REGEX 服务端大小写不敏感正则。
 * 从 PlatformSafetyGuardService 抽出的纯匹配逻辑，管理端测试与会话前拦截共用。
 * 正则不合法抛 IllegalArgumentException，由调用方决定映射（管理端 400，运行时跳过）。
 */
@Component
public class SafetyGuardMatcher {

    /** 返回命中的 pattern 列表；patterns 中空白项跳过。 */
    public List<String> match(String matchType, List<String> patterns, String input) {
        List<String> matched = new ArrayList<>();
        if (patterns == null || !StringUtils.hasText(input)) {
            return matched;
        }
        boolean regex = "REGEX".equalsIgnoreCase(matchType);
        for (String pattern : patterns) {
            if (!StringUtils.hasText(pattern)) {
                continue;
            }
            if (regex) {
                if (regexFind(pattern, input)) {
                    matched.add(pattern);
                }
            } else if (input.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT))) {
                matched.add(pattern);
            }
        }
        return matched;
    }

    private boolean regexFind(String pattern, String input) {
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(input).find();
        } catch (PatternSyntaxException ex) {
            throw new IllegalArgumentException("安全策略正则不合法：" + pattern, ex);
        }
    }
}
