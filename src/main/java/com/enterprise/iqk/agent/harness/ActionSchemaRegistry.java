package com.enterprise.iqk.agent.harness;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 动作 schema 注册表：集中声明全部 11 个动作（builtin 4 + mcp_call 1 + workspace 6）。
 * 构造器一次性注册、之后只读（LinkedHashMap + 读取时不可变拷贝），
 * 线程安全依赖"构造期写完、运行期只读"的单例初始化语义。
 */
@Component
public class ActionSchemaRegistry {
    private final Map<String, ActionSchema> schemas = new LinkedHashMap<>();

    /** 构造期一次性注册全部动作：builtin 4 个（读学校/读课程/预约/RAG 检索）、mcp_call 1 个、workspace 6 个（列文件/读文件/搜文本/提补丁/应用补丁/跑命令） */
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

        register(new ActionSchema("mcp_call", "mcp",
                Set.of("server", "tool", "arguments"), Set.of(),
                Set.of("arguments"), "external", true));

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
