package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HarnessEvaluationTest {

    @TempDir
    Path workspace;

    @Test
    void fixedHarnessCasesCoverBuiltinMcpWorkspaceAndPolicy() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "enterprise-agent-platform harness");
        AgentHarnessService service = new AgentHarnessService(
                List.of(fakeBuiltinRuntime(), fakeMcpRuntime(), new WorkspaceRuntime(workspace)),
                new ActionPolicyGuard(new ActionSchemaRegistry(), new AgentHarnessProperties()),
                mock(HarnessEventRecorder.class),
                new HarnessPayloadSanitizer()
        );

        AgentObservation builtin = service.execute(action("create_task",
                Map.of("title", "完成季度报告", "description", "整理Q3数据"), false));
        AgentObservation invalid = service.execute(action("query_school", Map.of(), false));
        AgentObservation mcp = service.execute(action("mcp_call",
                Map.of("server", "demo", "tool", "echo", "arguments", Map.of("text", "ok")), false));
        AgentObservation workspaceDenied = service.execute(action("workspace_read_file",
                Map.of("path", "README.md"), false));
        AgentObservation workspaceRead = service.execute(action("workspace_read_file",
                Map.of("path", "README.md"), true));

        assertThat(builtin.toMap()).containsEntry("status", "success");
        assertThat(invalid.toMap()).containsEntry("source", "policy");
        assertThat(mcp.toMap()).containsEntry("source", "mcp");
        assertThat(workspaceDenied.toMap()).containsEntry("source", "policy");
        assertThat(workspaceRead.toMap()).containsEntry("content", "enterprise-agent-platform harness");
    }

    private AgentRuntime fakeBuiltinRuntime() {
        return new AgentRuntime() {
            @Override
            public String source() {
                return "builtin";
            }

            @Override
            public boolean supports(String action) {
                return "create_task".equals(action);
            }

            @Override
            public AgentObservation execute(AgentAction action) {
                return AgentObservation.success(source(), List.of("school-a"), 1);
            }
        };
    }

    private McpToolRuntime fakeMcpRuntime() {
        return new McpToolRuntime(List.of(new McpToolAdapter() {
            @Override
            public String server() {
                return "demo";
            }

            @Override
            public String tool() {
                return "echo";
            }

            @Override
            public Object execute(Map<String, Object> arguments) {
                return Map.of("text", arguments.get("text"));
            }
        }));
    }

    private AgentAction action(String action, Map<String, Object> input, boolean trusted) {
        return new AgentAction(action, input, "prompt", "tenant", "chat",
                "balanced", "task-1", "step-1", trusted);
    }
}
