package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.service.platform.PlatformAssetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCatalogControllerTest {

    private final PlatformAssetService assetService = mock(PlatformAssetService.class);
    private final ModelCatalogController controller =
            new ModelCatalogController(assetService, new ObjectMapper());

    private static PlatformAsset asset(Long id, String name, String configJson) {
        PlatformAsset asset = new PlatformAsset();
        asset.setId(id);
        asset.setName(name);
        asset.setConfigJson(configJson);
        asset.setScope("SYSTEM");
        return asset;
    }

    @Test
    void listsOnlyLlmModelServicesWithDisplayFields() {
        when(assetService.list("model-services", null, 1, 100)).thenReturn(List.of(
                asset(1L, "Qwen Plus", "{\"category\":\"LLM\",\"modelName\":\"qwen-plus\"}"),
                asset(2L, "向量模型", "{\"category\":\"EMBEDDING\",\"modelName\":\"text-embedding-v4\"}"),
                asset(3L, "质地", "{\"category\":\"LLM\",\"model_name\":\"glm-5.3\"}")));

        List<ModelCatalogController.ChatModelOption> options = controller.options();

        assertThat(options).hasSize(2);
        assertThat(options.get(0).id()).isEqualTo(1L);
        assertThat(options.get(0).modelName()).isEqualTo("qwen-plus");
        assertThat(options.get(1).modelName()).isEqualTo("glm-5.3");
    }

    @Test
    void brokenConfigExcludedAndMissingModelNameKeptWithEmptyName() {
        when(assetService.list("model-services", null, 1, 100)).thenReturn(List.of(
                asset(1L, "坏配置", "{not-json"),
                asset(2L, "无模型名", "{\"category\":\"LLM\"}")));

        List<ModelCatalogController.ChatModelOption> options = controller.options();

        // 坏 configJson 解析不出类别，按非 LLM 过滤（不可选）；无模型名的 LLM 保留、模型名为空
        assertThat(options).hasSize(1);
        assertThat(options.get(0).id()).isEqualTo(2L);
        assertThat(options.get(0).modelName()).isEmpty();
    }
}
