package com.enterprise.iqk.service.kb;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 上传目标知识库校验与统计服务（FR-R5-01/02）：
 * null = 不归属；不存在/不可见、非 ACTIVE 都拒绝上传。
 */
class KnowledgeBaseCatalogServiceTest {

    private final PlatformAssetMapper assetMapper = mock(PlatformAssetMapper.class);
    private final IngestionJobMapper ingestionJobMapper = mock(IngestionJobMapper.class);
    private final KnowledgeBaseCatalogService service =
            new KnowledgeBaseCatalogService(assetMapper, ingestionJobMapper, new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    void nullOrNonPositiveTargetMeansUnboundUpload() {
        assertThat(service.resolveUploadTarget("tenant", null)).isNull();
        assertThat(service.resolveUploadTarget("tenant", 0L)).isNull();
    }

    @Test
    void missingOrInvisibleKnowledgeBaseIsRejected() {
        when(assetMapper.findByIdAndTypeVisible(9L, "tenant", "knowledge-bases")).thenReturn(null);

        assertThatThrownBy(() -> service.resolveUploadTarget("tenant", 9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或无权访问");
    }

    @Test
    void inactiveKnowledgeBaseIsRejected() {
        when(assetMapper.findByIdAndTypeVisible(3L, "tenant", "knowledge-bases"))
                .thenReturn(assetWithStatus("DISABLED"));

        assertThatThrownBy(() -> service.resolveUploadTarget("tenant", 3L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已停用");
    }

    @Test
    void activeKnowledgeBasePassesThrough() {
        when(assetMapper.findByIdAndTypeVisible(5L, "tenant", "knowledge-bases"))
                .thenReturn(assetWithStatus("ACTIVE"));

        assertThat(service.resolveUploadTarget("tenant", 5L)).isEqualTo(5L);
    }

    private PlatformAsset assetWithStatus(String status) {
        return PlatformAsset.builder()
                .id(1L)
                .tenantId("tenant")
                .assetType("knowledge-bases")
                .name("产品手册")
                .status(status)
                .build();
    }
}
