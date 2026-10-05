package com.enterprise.iqk.agent.harness;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerActionCatalogTest {
    private final PlannerActionCatalog catalog = new PlannerActionCatalog(new ActionSchemaRegistry());

    @Test
    void whitelistCoversNonTrustedActionsInRegistryOrderPlusFinish() {
        // 口径与 ActionPolicyGuard 一致：trustedOnly=false 才能在聊天循环被授权执行
        assertThat(catalog.plannerActions())
                .extracting(ActionSchema::action)
                .containsExactly("query_school", "query_course", "add_course_reservation", "rag_search",
                        "query_database", "mcp_call");
        assertThat(catalog.isPlannerAction("rag_search")).isTrue();
        assertThat(catalog.isPlannerAction("query_database")).isTrue();
        assertThat(catalog.isPlannerAction("finish")).isTrue();
        // 受信动作与未知名一律拒绝（由调用方强制归为 finish）
        assertThat(catalog.isPlannerAction("workspace_read_file")).isFalse();
        assertThat(catalog.isPlannerAction("delete_everything")).isFalse();
        assertThat(catalog.isPlannerAction(null)).isFalse();
    }

    @Test
    void standardBlockListsRegistryOrderWithHintAndFinish() {
        String block = catalog.standardActionsBlock();

        assertThat(block).startsWith("可选动作（只能从列表中选）：");
        // 无 hint 的动作保持裸名字（注册表顺序）
        assertThat(block).contains("\n- query_school\n- query_course\n- add_course_reservation\n- rag_search");
        // 带 hint 的动作附说明
        assertThat(block).contains("- query_database（直接查询主库业务表（只读）");
        assertThat(block).contains("- mcp_call（查询外部工具；当前提供天气查询，action_input 固定为 "
                + "{\"server\":\"weather\",\"tool\":\"get_weather\",\"arguments\":{\"city\":\"城市中文名\"}}）");
        assertThat(block).endsWith("\n- finish");
        // 受信动作绝不进提示词
        assertThat(block).doesNotContain("workspace");
    }

    @Test
    void workflowSectionListsSlashLinePlusHintLines() {
        String section = catalog.workflowActionsSection();

        assertThat(section).startsWith(
                "可选动作：query_school / query_course / add_course_reservation / rag_search / query_database / mcp_call / finish");
        assertThat(section).contains("\nquery_database（直接查询主库业务表");
        assertThat(section).contains("\nmcp_call（查询外部工具；当前提供天气查询");
        assertThat(section).doesNotContain("workspace");
    }
}
