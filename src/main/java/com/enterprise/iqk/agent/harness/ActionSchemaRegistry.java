package com.enterprise.iqk.agent.harness;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 动作 schema 注册表：集中声明全部 12 个动作（builtin 5 + mcp_call 1 + workspace 6）。
 * 构造器一次性注册、之后只读（LinkedHashMap + 读取时不可变拷贝），
 * 线程安全依赖"构造期写完、运行期只读"的单例初始化语义。
 * 两条 ReAct 链路的规划器提示词动作列表与解析白名单均由 {@link PlannerActionCatalog}
 * 从本注册表生成——新增动作在这里登记一次即可，不要在引擎/解析器里再手抄名单。
 */
@Component
public class ActionSchemaRegistry {
    private final Map<String, ActionSchema> schemas = new LinkedHashMap<>();

    /** 构造期一次性注册全部动作：builtin 5 个（读学校/读课程/预约/RAG 检索/主库只读查询）、mcp_call 1 个、workspace 6 个（列文件/读文件/搜文本/提补丁/应用补丁/跑命令） */
    public ActionSchemaRegistry() {
        register(new ActionSchema("query_school", "builtin",
                Set.of(), Set.of(), Set.of(), "read", false));
        register(new ActionSchema("query_course", "builtin",
                Set.of(), Set.of("type", "edu", "sorts"), Set.of(), "read", false));
        register(new ActionSchema("add_course_reservation", "builtin",
                Set.of("course", "studentName", "contactInfo", "school"),
                Set.of("remark"), Set.of("contactInfo"), "write", false));
        register(new ActionSchema("rag_search", "builtin",
                Set.of(), Set.of("query"), Set.of(), "read", false));
        // query_database：模型现场生成 SQL 的只读查询。tenantId 不开放为入参（防伪造），
        // 由 BuiltinToolRuntime 从 action.tenantId() 服务端注入；
        // 风险由 SqlReadOnlyGuard（只读/单语句/敏感表黑名单/LIMIT 收敛）
        // + DatabaseQueryTools 的只读会话/超时/行数截断兜住，
        // 可被 disabled-actions / 租户白名单 / database-query.enabled 三层随时熔断。
        register(new ActionSchema("query_database", "builtin",
                Set.of("sql"), Set.of(), Set.of(), "read", false,
                "直接查询主库业务表（只读）。action_input 示例："
                        + "{\"sql\":\"SELECT id, name, price FROM course WHERE tenant_id = 'public' LIMIT 10\"}。"
                        + "只能一条 SELECT 语句（可为 WITH 开头的 CTE）；业务表必须带 tenant_id = '当前租户' 过滤条件；"
                        + "禁止 INSERT/UPDATE/DELETE 等任何写操作与多语句；结果自动追加 LIMIT 并截断。"));

        // mcp_call 不设 trustedOnly：外部只读查询（如天气）要能在聊天 ReAct 循环里直接用，
        // 风险由适配器的 SSRF 校验 + allowed-hosts 白名单 + 2MiB 响应上限兜住，
        // 还可被 disabled-actions / 租户白名单随时熔断；
        // workspace 写/壳动作保持 trustedOnly（一次性令牌确认流程专属）。
        register(new ActionSchema("mcp_call", "mcp",
                Set.of("server", "tool", "arguments"), Set.of(),
                Set.of("arguments"), "external", false,
                "查询外部工具；当前提供天气查询，action_input 固定为 "
                        + "{\"server\":\"weather\",\"tool\":\"get_weather\",\"arguments\":{\"city\":\"城市中文名\"}}"));

        register(new ActionSchema("workspace_list_files", "workspace",
                Set.of(), Set.of("path", "maxDepth"), Set.of(), "read", true));
        register(new ActionSchema("workspace_read_file", "workspace",
                Set.of("path"), Set.of("maxBytes"), Set.of(), "read", true));
        register(new ActionSchema("workspace_search_text", "workspace",
                Set.of("query"), Set.of("path", "maxMatches"), Set.of(), "read", true));
        register(new ActionSchema("workspace_propose_patch", "workspace",
                Set.of("path"), Set.of("content", "patch", "summary"), Set.of("content", "patch"),
                "write_preview", true));
        register(new ActionSchema("workspace_apply_patch", "workspace",
                Set.of("path"), Set.of("content", "patch", "summary"), Set.of("content", "patch"),
                "write", true));
        register(new ActionSchema("workspace_run_shell", "workspace",
                Set.of("command"), Set.of("timeoutSeconds"), Set.of("command"), "shell", true));
    }

    /** 按动作名查找 schema，不存在返回 empty（调用方据此拒绝未知动作） */
    public Optional<ActionSchema> find(String action) {
        return Optional.ofNullable(schemas.get(action));
    }

    /** 全部动作名（不可变视图） */
    public Set<String> actions() {
        return Set.copyOf(schemas.keySet());
    }

    /** 全部 schema 列表（不可变视图） */
    public List<ActionSchema> list() {
        return List.copyOf(schemas.values());
    }

    /** 注册（仅构造期调用） */
    private void register(ActionSchema schema) {
        schemas.put(schema.action(), schema);
    }
}
