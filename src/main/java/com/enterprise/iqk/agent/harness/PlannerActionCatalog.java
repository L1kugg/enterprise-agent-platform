package com.enterprise.iqk.agent.harness;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 规划器动作目录：从 {@link ActionSchemaRegistry} 单一来源生成两条 ReAct 链路共用的
 * 解析白名单与提示词动作列表，新增动作只需在注册表登记一次。
 * 白名单口径与 ActionPolicyGuard 的"聊天循环只能执行非受信动作"不变量一致：
 * trustedOnly=false 的动作才进白名单和提示词（workspace 写/壳动作由此自动排除）。
 */
@Component
public class PlannerActionCatalog {
    /** finish 不是工具、不在注册表里，是两条链路共用的收尾动作 */
    public static final String FINISH = "finish";

    private final ActionSchemaRegistry registry;
    private final Set<String> whitelist;

    public PlannerActionCatalog(ActionSchemaRegistry registry) {
        this.registry = registry;
        this.whitelist = Set.copyOf(plannerActions().stream()
                .map(ActionSchema::action)
                .collect(Collectors.toSet()));
    }

    /** 规划器可见动作（注册表顺序）：trustedOnly=false 才能在聊天循环里被授权执行 */
    public List<ActionSchema> plannerActions() {
        return registry.list().stream()
                .filter(schema -> !schema.trustedOnly())
                .toList();
    }

    /** 动作是否在规划器白名单内（含 finish）；白名单外由调用方强制归为 finish */
    public boolean isPlannerAction(String action) {
        return FINISH.equals(action) || (action != null && whitelist.contains(action));
    }

    /** 标准引擎（ReactAgentService）的动作列表段：每动作一行 bullet，带 hint 的附说明 */
    public String standardActionsBlock() {
        StringBuilder block = new StringBuilder("可选动作（只能从列表中选）：");
        for (ActionSchema schema : plannerActions()) {
            block.append("\n- ").append(schema.action());
            if (StringUtils.hasText(schema.plannerHint())) {
                block.append("（").append(schema.plannerHint()).append("）");
            }
        }
        block.append("\n- ").append(FINISH);
        return block.toString();
    }

    /** workflow 引擎（WorkflowReactAgentService）的动作段：slash 一行 + 带 hint 动作的说明行 */
    public String workflowActionsSection() {
        String names = plannerActions().stream()
                .map(ActionSchema::action)
                .collect(Collectors.joining(" / "))
                + " / " + FINISH;
        StringBuilder section = new StringBuilder("可选动作：").append(names);
        plannerActions().stream()
                .filter(schema -> StringUtils.hasText(schema.plannerHint()))
                .forEach(schema -> section.append("\n").append(schema.action())
                        .append("（").append(schema.plannerHint()).append("）"));
        return section.toString();
    }
}
