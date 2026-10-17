package com.enterprise.iqk.service.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.llm.PlatformChatModelRegistry;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.enterprise.iqk.service.agent.AgentBindingSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformAssetServiceTest {
    @Mock
    private PlatformAssetMapper mapper;

    @Test
    void createStoresRawSecretButReturnsMaskedConfiguration() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", "n/a", "ROLE_ADMIN"));
        try {
            PlatformAssetService service = new PlatformAssetService(mapper, new ObjectMapper(),
                    mock(AgentBindingSyncService.class), mock(PlatformChatModelRegistry.class),
                    mock(com.enterprise.iqk.mapper.IngestionJobMapper.class),
                    mock(com.enterprise.iqk.retrieval.KeywordIndexStore.class));
            PlatformAsset created = service.create("model-services", "qwen", null,
                    "{\"apiUrl\":\"https://model.local\",\"apiKey\":\"sk-secret\"}", null, "tester");

            ArgumentCaptor<PlatformAsset> captor = ArgumentCaptor.forClass(PlatformAsset.class);
            verify(mapper).insert(captor.capture());
            assertThat(created.getConfigJson()).contains("\"apiKey\":\"******\"")
                    .doesNotContain("sk-secret");
            verify(mapper).insert(any(PlatformAsset.class));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void createRejectsUnsupportedAssetType() {
        PlatformAssetService service = new PlatformAssetService(mapper, new ObjectMapper(),
                    mock(AgentBindingSyncService.class), mock(PlatformChatModelRegistry.class),
                    mock(com.enterprise.iqk.mapper.IngestionJobMapper.class),
                    mock(com.enterprise.iqk.retrieval.KeywordIndexStore.class));
        assertThatThrownBy(() -> service.create("unknown", "x", "{}", null, null, "tester"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void createRejectsInvalidConfigurationJson() {
        PlatformAssetService service = new PlatformAssetService(mapper, new ObjectMapper(),
                    mock(AgentBindingSyncService.class), mock(PlatformChatModelRegistry.class),
                    mock(com.enterprise.iqk.mapper.IngestionJobMapper.class),
                    mock(com.enterprise.iqk.retrieval.KeywordIndexStore.class));
        assertThatThrownBy(() -> service.create("agents", "x", null, "{bad", null, "tester"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void getIsRestrictedToCurrentTenantAndAssetType() {
        when(mapper.findByIdAndTypeVisible(any(), any(), any())).thenReturn(null);
        PlatformAssetService service = new PlatformAssetService(mapper, new ObjectMapper(),
                    mock(AgentBindingSyncService.class), mock(PlatformChatModelRegistry.class),
                    mock(com.enterprise.iqk.mapper.IngestionJobMapper.class),
                    mock(com.enterprise.iqk.retrieval.KeywordIndexStore.class));

        assertThatThrownBy(() -> service.get("agents", 12L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
    }


    @Test
    void updateKeepsOriginalSecretWhenMaskedOrOmitted() {
        PlatformAsset existing = PlatformAsset.builder()
                .id(9L).tenantId("public").assetType("model-services").scope("SYSTEM").name("model")
                .status("UNTESTED").configJson("{\"apiKey\":\"sk-original\"}")
                .createdAt(java.time.LocalDateTime.now()).updatedAt(java.time.LocalDateTime.now())
                .build();
        when(mapper.findByIdAndTypeVisible(9L, "public", "model-services")).thenReturn(existing);
        final String[] rawAtUpdate = new String[1];
        doAnswer(invocation -> {
            PlatformAsset argument = invocation.getArgument(0);
            rawAtUpdate[0] = argument.getConfigJson();
            return 1;
        }).when(mapper).updateById(any(PlatformAsset.class));
        PlatformAssetService service = new PlatformAssetService(mapper, new ObjectMapper(),
                    mock(AgentBindingSyncService.class), mock(PlatformChatModelRegistry.class),
                    mock(com.enterprise.iqk.mapper.IngestionJobMapper.class),
                    mock(com.enterprise.iqk.retrieval.KeywordIndexStore.class));

        PlatformAsset updated = service.update("model-services", 9L, null, null,
                "{\"apiKey\":\"******\"}", "UNTESTED", null);

        assertThat(updated.getConfigJson()).contains("\"apiKey\":\"******\"");
        assertThat(rawAtUpdate[0]).contains("sk-original").doesNotContain("\"apiKey\":\"******\"");
        // scope 不可经更新变更（update 签名不接收 scope）
        assertThat(updated.getScope()).isEqualTo("SYSTEM");
        verify(mapper).updateById(existing);    }

    @Test
    void createRejectsSystemScopeForNonCredentialType() {
        PlatformAssetService service = new PlatformAssetService(mapper, new ObjectMapper(),
                    mock(AgentBindingSyncService.class), mock(PlatformChatModelRegistry.class),
                    mock(com.enterprise.iqk.mapper.IngestionJobMapper.class),
                    mock(com.enterprise.iqk.retrieval.KeywordIndexStore.class));
        // 六类业务资产不允许系统共享（白名单外直接 400）
        assertThatThrownBy(() -> service.create("agents", "x", null, "{}", null, "tester", "SYSTEM"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void createSystemScopedAssetLandsInPublicTenant() {
        when(mapper.insert(any(PlatformAsset.class))).thenAnswer(invocation -> {
            PlatformAsset asset = invocation.getArgument(0);
            asset.setId(77L);
            return 1;
        });
        PlatformAssetService service = new PlatformAssetService(mapper, new ObjectMapper(),
                mock(AgentBindingSyncService.class), mock(PlatformChatModelRegistry.class),
                mock(com.enterprise.iqk.mapper.IngestionJobMapper.class),
                mock(com.enterprise.iqk.retrieval.KeywordIndexStore.class));

        PlatformAsset created = service.create("model-services", "shared-model", null,
                "{\"apiUrl\":\"https://model.local\"}", null, "tester", "SYSTEM");

        ArgumentCaptor<PlatformAsset> captor = ArgumentCaptor.forClass(PlatformAsset.class);
        verify(mapper).insert(captor.capture());
        // SYSTEM 行固定落 public 主租户 + scope 标记
        assertThat(captor.getValue().getTenantId()).isEqualTo("public");
        assertThat(captor.getValue().getScope()).isEqualTo("SYSTEM");
        assertThat(created.getScope()).isEqualTo("SYSTEM");
    }

    @Test
    void listUsesUnionQueryOnlyForCredentialTypes() {
        PlatformAssetService service = new PlatformAssetService(mapper, new ObjectMapper(),
                mock(AgentBindingSyncService.class), mock(PlatformChatModelRegistry.class),
                mock(com.enterprise.iqk.mapper.IngestionJobMapper.class),
                mock(com.enterprise.iqk.retrieval.KeywordIndexStore.class));

        service.list("model-services", null, 1, 20);
        verify(mapper).findForListUnion(org.mockito.ArgumentMatchers.eq("public"),
                org.mockito.ArgumentMatchers.eq("model-services"), any(),
                org.mockito.ArgumentMatchers.eq(20), org.mockito.ArgumentMatchers.eq(0L));

        service.list("agents", null, 1, 20);
        verify(mapper).findForList(org.mockito.ArgumentMatchers.eq("public"),
                org.mockito.ArgumentMatchers.eq("agents"), any(),
                org.mockito.ArgumentMatchers.eq(20), org.mockito.ArgumentMatchers.eq(0L));
    }

    @Test
    void updateRejectsCrossTenantPrivateAsset() {
        // 联合查询只放行 SYSTEM 行：别的租户的私有行（scope=TENANT）解析为 null → 404
        when(mapper.findByIdAndTypeVisible(9L, "public", "model-services")).thenReturn(null);
        PlatformAssetService service = new PlatformAssetService(mapper, new ObjectMapper(),
                mock(AgentBindingSyncService.class), mock(PlatformChatModelRegistry.class),
                mock(com.enterprise.iqk.mapper.IngestionJobMapper.class),
                mock(com.enterprise.iqk.retrieval.KeywordIndexStore.class));

        assertThatThrownBy(() -> service.update("model-services", 9L, "new-name", null, null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
    }
}
