package com.enterprise.iqk.service.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
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
            PlatformAssetService service = new PlatformAssetService(mapper, new ObjectMapper());
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
        PlatformAssetService service = new PlatformAssetService(mapper, new ObjectMapper());
        assertThatThrownBy(() -> service.create("unknown", "x", "{}", null, null, "tester"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void createRejectsInvalidConfigurationJson() {
        PlatformAssetService service = new PlatformAssetService(mapper, new ObjectMapper());
        assertThatThrownBy(() -> service.create("agents", "x", null, "{bad", null, "tester"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void getIsRestrictedToCurrentTenantAndAssetType() {
        when(mapper.findByIdAndType(any(), any(), any())).thenReturn(null);
        PlatformAssetService service = new PlatformAssetService(mapper, new ObjectMapper());

        assertThatThrownBy(() -> service.get("agents", 12L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
    }
}

