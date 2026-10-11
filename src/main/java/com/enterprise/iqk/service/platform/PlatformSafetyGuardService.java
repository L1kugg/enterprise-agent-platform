package com.enterprise.iqk.service.platform;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.service.agent.SafetyGuardMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 安全防护策略测试：KEYWORD 大小写不敏感包含匹配，REGEX 服务端正则匹配（匹配核心复用 SafetyGuardMatcher）。 */
@Service
@RequiredArgsConstructor
public class PlatformSafetyGuardService {
    private final PlatformAssetMapper assetMapper;
    private final ObjectMapper objectMapper;
    private final SafetyGuardMatcher safetyGuardMatcher;

    public Map<String, Object> test(Long id, String text) {
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "测试文本不能为空");
        }
        PlatformAsset guard = assetMapper.findByIdAndType(id, TenantContext.currentTenantId(), "safety-guards");
        if (guard == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "安全策略不存在");
        JsonNode config = parse(guard.getConfigJson());
        if ("DISABLED".equalsIgnoreCase(guard.getStatus()) || "DISABLED".equalsIgnoreCase(config.path("status").asText())) {
            return Map.of("blocked", false, "hitLevel", "NONE", "matchedFragments", List.of());
        }

        List<String> matched;
        try {
            matched = safetyGuardMatcher.match(config.path("matchType").asText("KEYWORD"),
                    stringList(config.path("patterns")), text);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
        boolean blocked = !matched.isEmpty();
        return Map.of(
                "blocked", blocked,
                "hitLevel", blocked ? config.path("hitLevel").asText("HIGH") : "NONE",
                "matchedFragments", matched,
                "blockMessage", blocked ? config.path("blockMessage").asText("该话题不在允许讨论范围内。") : ""
        );
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

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "安全策略配置 JSON 不合法", ex);
        }
    }
}
