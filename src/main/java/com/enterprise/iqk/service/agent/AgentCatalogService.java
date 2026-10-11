package com.enterprise.iqk.service.agent;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.domain.vo.AgentCatalogItemVO;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/** 智能体目录（FR-R1-04）：给普通用户列出可选的已发布智能体。
 *  只透出展示字段（名称/描述/开场白/引导问题），配置细节不出服务层；
 *  配置 JSON 解析失败按缺省处理，单条坏数据不影响整个目录。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCatalogService {

    private final PlatformAssetMapper assetMapper;
    private final ObjectMapper objectMapper;

    public List<AgentCatalogItemVO> listUsable(String tenantId) {
        return assetMapper.findPublishedByType(tenantId, "agents").stream()
                .map(this::toItem)
                .toList();
    }

    private AgentCatalogItemVO toItem(PlatformAsset asset) {
        JsonNode config = parseQuietly(asset);
        return AgentCatalogItemVO.builder()
                .id(asset.getId())
                .name(asset.getName())
                .description(asset.getDescription())
                .openingMessage(textOrNull(config.path("openingMessage")))
                .openingQuestions(stringList(config.path("openingQuestions")))
                .build();
    }

    private JsonNode parseQuietly(PlatformAsset asset) {
        try {
            String configJson = asset.getConfigJson();
            return objectMapper.readTree(
                    StringUtils.hasText(configJson) ? configJson : "{}");
        } catch (Exception ex) {
            log.warn("智能体目录配置解析失败，按空配置展示: agentId={}, reason={}", asset.getId(), ex.toString());
            return objectMapper.createObjectNode();
        }
    }

    private String textOrNull(JsonNode node) {
        String text = node.asText("");
        return text.isBlank() ? null : text;
    }

    private List<String> stringList(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> values = new java.util.ArrayList<>();
        for (JsonNode item : array) {
            String text = item.asText("");
            if (!text.isBlank()) {
                values.add(text.trim());
            }
        }
        return values;
    }
}
