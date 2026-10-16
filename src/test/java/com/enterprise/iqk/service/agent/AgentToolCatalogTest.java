package com.enterprise.iqk.service.agent;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.AgentBindingMapper;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentToolCatalogTest {

    @Mock
    private AgentBindingMapper bindingMapper;

    @Mock
    private PlatformAssetMapper assetMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentToolCatalog catalog() {
        return new AgentToolCatalog(bindingMapper, assetMapper, objectMapper);
    }

    private PlatformAsset tool(Long id, String name, String configJson) {
        return PlatformAsset.builder()
                .id(id).tenantId("t-1").assetType("tools").name(name)
                .description("按城市查询经纬度")
                .status("ENABLED").configJson(configJson).build();
    }

    private com.enterprise.iqk.domain.agent.AgentBindingRow binding(long resourceId) {
        return com.enterprise.iqk.domain.agent.AgentBindingRow.builder()
                .resourceId(resourceId).resourceType("tools").bindingOrder(1).build();
    }

    @Test
    void emptyBindingsProduceEmptySection() {
        when(bindingMapper.findByAgent(eq("agent_tool_binding"), eq("t-1"), eq(7L)))
                .thenReturn(List.of());

        assertThat(catalog().catalogSection(7L, "t-1")).isEmpty();
    }

    @Test
    void nullAgentIdProducesEmptySection() {
        assertThat(catalog().catalogSection(null, "t-1")).isEmpty();
        assertThat(catalog().catalogSection(0L, "t-1")).isEmpty();
    }

    @Test
    void rendersToolLineWithNameTypeAndRequiredParams() {
        when(bindingMapper.findByAgent(eq("agent_tool_binding"), eq("t-1"), eq(7L)))
                .thenReturn(List.of(binding(4L)));
        when(assetMapper.findByIdAndTypeVisible(4L, "t-1", "tools"))
                .thenReturn(tool(4L, "汇率查询",
                        "{\"toolType\":\"FORM_API\",\"params\":[{\"name\":\"base\",\"location\":\"PATH\","
                                + "\"required\":true},{\"name\":\"date\",\"required\":false}]}"));

        String section = catalog().catalogSection(7L, "t-1");

        assertThat(section)
                .contains("tool_call")
                .contains("- 汇率查询（FORM_API）")
                .contains("必填参数：base")
                .contains("按城市查询经纬度");
    }

    @Test
    void skipsMissingOrDisabledTools() {
        when(bindingMapper.findByAgent(eq("agent_tool_binding"), eq("t-1"), eq(7L)))
                .thenReturn(List.of(binding(4L), binding(5L)));
        when(assetMapper.findByIdAndTypeVisible(4L, "t-1", "tools"))
                .thenReturn(tool(4L, "汇率查询", "{\"toolType\":\"SQL\"}"));
        when(assetMapper.findByIdAndTypeVisible(5L, "t-1", "tools"))
                .thenReturn(PlatformAsset.builder()
                        .id(5L).assetType("tools").name("停用工具").status("DISABLED").build());

        String section = catalog().catalogSection(7L, "t-1");

        assertThat(section).contains("汇率查询（SQL）").doesNotContain("停用工具");
    }
}
