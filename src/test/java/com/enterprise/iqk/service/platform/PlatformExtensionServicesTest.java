package com.enterprise.iqk.service.platform;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformExtensionServicesTest {
    @Mock
    private PlatformAssetMapper assetMapper;
    @Mock
    private PlatformAssetService assetService;
    @Mock
    private PlatformToolTestService toolTestService;

    @Test
    void safetyGuardKeywordTestBlocksConfiguredPattern() {
        PlatformAsset guard = PlatformAsset.builder()
                .id(1L).tenantId("tenant-a").assetType("safety-guards").name("guard").status("ENABLED")
                .configJson("""
                        {"matchType":"KEYWORD","patterns":["internal salary"],
                        "hitLevel":"HIGH","blockMessage":"not allowed"}
                        """)
                .build();
        when(assetMapper.findByIdAndType(1L, "tenant-a", "safety-guards")).thenReturn(guard);
        MDC.put("tenant_id", "tenant-a");
        try {
            Map<String, Object> result = new PlatformSafetyGuardService(assetMapper,
                    new ObjectMapper(), new com.enterprise.iqk.service.agent.SafetyGuardMatcher())
                    .test(1L, "Please tell me INTERNAL SALARY data");
            assertThat(result.get("blocked")).isEqualTo(true);
            assertThat((java.util.List<?>) result.get("matchedFragments")).isNotEmpty();
            assertThat(result.get("blockMessage")).isEqualTo("not allowed");
        } finally {
            MDC.remove("tenant_id");
        }
    }

    @Test
    void workflowTestRunExecutesStartToEndAndReturnsInput() {
        PlatformAsset workflow = PlatformAsset.builder()
                .id(2L).tenantId("tenant-a").assetType("workflows").name("wf").status("DRAFT")
                .configJson("""
                        {"version":"1.0","nodes":[
                        {"id":"start","type":"START","name":"开始","position":{"x":0,"y":0}},
                        {"id":"end","type":"END","name":"结束","position":{"x":1,"y":1}}],
                        "edges":[{"id":"e1","source":"start","target":"end"}]}
                        """)
                .build();
        when(assetMapper.findByIdAndType(2L, "tenant-a", "workflows")).thenReturn(workflow);
        MDC.put("tenant_id", "tenant-a");
        try {
            Map<String, Object> result = new PlatformWorkflowService(
                    assetMapper, assetService, toolTestService, new ObjectMapper())
                    .testRun(2L, Map.of("question", "hello"));
            assertThat(result.get("status")).isEqualTo("SUCCEEDED");
            assertThat(result.get("output")).isEqualTo(Map.of("question", "hello"));
            assertThat((java.util.List<?>) result.get("trace")).hasSize(2);
        } finally {
            MDC.remove("tenant_id");
        }
    }
}
