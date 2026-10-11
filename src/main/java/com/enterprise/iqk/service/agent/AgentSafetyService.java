package com.enterprise.iqk.service.agent;

import com.enterprise.iqk.domain.agent.AgentRuntimeConfig;
import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体运行时安全拦截（FR-R1-06 第 1 步）：按绑定顺序（binding_order）遍历
 * 启用中的安全防护，对用户输入做会话前检查，首个命中即短路，后续不再进入模型调用。
 * 引用不存在/已停用/正则非法的防护按“跳过+告警”处理，可用性优先，不打断会话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSafetyService {

    private final PlatformAssetMapper assetMapper;
    private final SafetyGuardMatcher matcher;
    private final ObjectMapper objectMapper;

    public SafetyMatchResult interceptInput(String tenantId, AgentRuntimeConfig cfg, String input) {
        if (cfg == null || cfg.safetyGuardIds() == null || cfg.safetyGuardIds().isEmpty()) {
            return SafetyMatchResult.notBlocked();
        }
        boolean checked = false;
        for (Long guardId : cfg.safetyGuardIds()) {
            PlatformAsset guard = assetMapper.findByIdAndType(guardId, tenantId, "safety-guards");
            if (guard == null) {
                log.warn("智能体 {} 绑定的安全防护 {} 不存在，跳过", cfg.agentId(), guardId);
                continue;
            }
            JsonNode config = parse(guard.getConfigJson());
            if ("DISABLED".equalsIgnoreCase(guard.getStatus())
                    || "DISABLED".equalsIgnoreCase(config.path("status").asText())) {
                continue;
            }
            checked = true;
            List<String> patterns = stringList(config.path("patterns"));
            List<String> matched;
            try {
                matched = matcher.match(config.path("matchType").asText("KEYWORD"), patterns, input);
            } catch (IllegalArgumentException ex) {
                log.warn("智能体 {} 绑定的安全防护 {} 正则非法已跳过: {}", cfg.agentId(), guardId, ex.getMessage());
                continue;
            }
            if (!matched.isEmpty()) {
                return new SafetyMatchResult(
                        true,
                        true,
                        guardId,
                        guard.getName(),
                        config.path("hitLevel").asText("HIGH"),
                        matched,
                        config.path("blockMessage").asText("该话题不在允许讨论范围内。")
                );
            }
        }
        return new SafetyMatchResult(checked, false, null, null, "NONE", List.of(), "");
    }

    private List<String> stringList(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                String text = item.asText("");
                if (!text.isBlank()) {
                    values.add(text.trim());
                }
            }
        }
        return values;
    }

    private JsonNode parse(String configJson) {
        try {
            return objectMapper.readTree(configJson == null || configJson.isBlank() ? "{}" : configJson);
        } catch (Exception ex) {
            log.warn("安全防护配置 JSON 不合法，按无规则处理: {}", ex.toString());
            return objectMapper.createObjectNode();
        }
    }
}
