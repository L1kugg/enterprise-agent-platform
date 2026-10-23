package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.service.platform.PlatformAssetService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对话栏模型选择目录（用户侧）：本租户私有 ∪ 系统共享的 LLM 模型服务，
 * 只透出选择所需的 id/名称/模型名/范围，不含任何配置与密钥。
 * 与 /ai/agents 同一认证口径（能发起会话即可见）。
 */
@RestController
@RequestMapping("/ai/models")
@RequiredArgsConstructor
public class ModelCatalogController {

    private final PlatformAssetService assetService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public List<ChatModelOption> options() {
        // 模型服务数量级很小（平台级资产），一页拉全后按类别过滤
        List<PlatformAsset> assets = assetService.list("model-services", null, 1, 100);
        return assets.stream()
                .filter(asset -> "LLM".equalsIgnoreCase(categoryOf(asset)))
                .map(asset -> new ChatModelOption(
                        asset.getId(), asset.getName(), modelNameOf(asset), asset.getScope()))
                .toList();
    }

    private String categoryOf(PlatformAsset asset) {
        return readConfig(asset).path("category").asText("");
    }

    private String modelNameOf(PlatformAsset asset) {
        JsonNode config = readConfig(asset);
        String modelName = config.path("modelName").asText(config.path("model_name").asText(""));
        return modelName.isBlank() ? config.path("modelParams").path("model").asText("") : modelName;
    }

    private JsonNode readConfig(PlatformAsset asset) {
        try {
            return objectMapper.readTree(asset.getConfigJson());
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    /** 对话栏模型选项。 */
    public record ChatModelOption(Long id, String name, String modelName, String scope) {
    }
}
