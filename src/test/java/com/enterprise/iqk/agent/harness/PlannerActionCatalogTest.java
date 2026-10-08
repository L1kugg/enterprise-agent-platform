package com.enterprise.iqk.agent.harness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlannerActionCatalogTest {
    private final McpToolCatalog mcpCatalog = mock(McpToolCatalog.class);
    private final PlannerActionCatalog catalog = new PlannerActionCatalog(new ActionSchemaRegistry(), mcpCatalog);

    @BeforeEach
    void setUp() {
        when(mcpCatalog.plannerHint()).thenReturn("查询外部工具");
    }

    @Test
    void whitelistCoversNonTrustedActionsInRegistryOrderPlusFinish() {
        assertThat(catalog.isPlannerAction("finish")).isTrue();
        assertThat(catalog.isPlannerAction("create_task")).isTrue();
        assertThat(catalog.isPlannerAction("rag_search")).isTrue();
        assertThat(catalog.isPlannerAction("query_database")).isTrue();
        assertThat(catalog.isPlannerAction("mcp_call")).isTrue();
        assertThat(catalog.isPlannerAction("workspace_read_file")).isFalse();
        assertThat(catalog.isPlannerAction("random_thing")).isFalse();
    }

    @Test
    void standardActionsBlockListsEachActionWithHint() {
        String block = catalog.standardActionsBlock();
        assertThat(block).contains("create_task").contains("rag_search").contains("query_database").contains("mcp_call").contains("finish");
    }

    @Test
    void workflowSectionListsActionsAndHints() {
        String section = catalog.workflowActionsSection();
        assertThat(section).contains("create_task").contains("rag_search").contains("query_database").contains("mcp_call").contains("finish");
    }
}