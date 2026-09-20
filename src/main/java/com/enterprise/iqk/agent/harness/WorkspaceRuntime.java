package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 工作区运行时：Agent 在受限文件系统沙箱内执行 6 个动作（列文件/读文件/搜文本/提补丁/应用补丁/跑命令）。
 * 安全模型：路径经 resolvePath 归一化必须仍在根内（防 ../ 逃逸）；写入与 shell 各有开关；
 * 命令白名单（pwd/ls/rg/git/mvn test）+ git flag 黑名单 + mvn -D 属性白名单；读写与输出全有上限。
 */
@Component
public class WorkspaceRuntime implements AgentRuntime {
    private static final Set<String> SUPPORTED_ACTIONS = Set.of(
            "workspace_list_files",
            "workspace_read_file",
            "workspace_search_text",
            "workspace_propose_patch",
            "workspace_apply_patch",
            "workspace_run_shell"
    );

    private final AgentHarnessProperties harnessProperties;
    private final UnifiedDiffService diffService;
    private final Path workspaceRoot;

    /** 生产构造器：工作区根取自配置；根目录归一化为绝对路径，resolvePath 的逃逸判断以此为准 */
    @Autowired
    public WorkspaceRuntime(AgentHarnessProperties harnessProperties, UnifiedDiffService diffService) {
        this(harnessProperties, diffService, Path.of(harnessProperties.getWorkspace().getRoot()));
    }

    WorkspaceRuntime(Path workspaceRoot) {
        this(defaultProperties(workspaceRoot), new UnifiedDiffService(), workspaceRoot);
    }

    WorkspaceRuntime(AgentHarnessProperties harnessProperties, UnifiedDiffService diffService, Path workspaceRoot) {
        this.harnessProperties = harnessProperties;
        this.diffService = diffService;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    @Override
    public String source() {
        return "workspace";
    }

    @Override
    public boolean supports(String action) {
        return SUPPORTED_ACTIONS.contains(action);
    }

    @Override
    public AgentObservation execute(AgentAction action) {
        long startedNs = System.nanoTime();
        try {
            Map<String, Object> payload = switch (action.action()) {
                case "workspace_list_files" -> listFiles(action.actionInput());
                case "workspace_read_file" -> readFile(action.actionInput());
                case "workspace_search_text" -> searchText(action.actionInput());
                case "workspace_propose_patch" -> proposePatch(action.actionInput());
                case "workspace_apply_patch" -> applyPatch(action.actionInput());
                case "workspace_run_shell" -> runCommand(action.actionInput());
                default -> Map.of("status", "error", "message", "unsupported action: " + action.action());
            };
            if ("error".equals(payload.get("status"))) {
                Object message = payload.get("message");
                return AgentObservation.error(source(), String.valueOf(message), elapsedMs(startedNs));
            }
            return AgentObservation.success(source(), payload, elapsedMs(startedNs));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return AgentObservation.error(source(), "workspace action failed: " + ex.getMessage(), elapsedMs(startedNs));
        } catch (IOException ex) {
            return AgentObservation.error(source(), "workspace action failed: " + ex.getMessage(), elapsedMs(startedNs));
        } catch (RuntimeException ex) {
            return AgentObservation.error(source(), "workspace action failed: " + ex.getMessage(), elapsedMs(startedNs));
        }
    }

    private Map<String, Object> listFiles(Map<String, Object> input) throws IOException {
        Path root = resolvePath(stringVal(input, "path", "."));
        int maxDepth = Math.min(Math.max(intVal(input.get("maxDepth"), 2), 0), 5);
        List<Map<String, Object>> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, maxDepth)) {
            stream.sorted(Comparator.naturalOrder())
                    .limit(200)
                    .forEach(path -> files.add(fileSummary(path)));
        }
        return Map.of("root", relative(root), "files", files);
    }

    private Map<String, Object> readFile(Map<String, Object> input) throws IOException {
        Path path = resolvePath(stringVal(input, "path", ""));
        if (!Files.isRegularFile(path)) {
            return Map.of("status", "error", "message", "file not found: " + relative(path));
        }
        int maxFileBytes = Math.max(1, harnessProperties.getWorkspace().getMaxFileBytes());
        int maxBytes = Math.min(Math.max(intVal(input.get("maxBytes"), maxFileBytes), 1), maxFileBytes);
        byte[] bytes = readPrefix(path, maxBytes);
        return Map.of(
                "path", relative(path),
                "content", new String(bytes, StandardCharsets.UTF_8),
                "truncated", Files.size(path) > bytes.length
        );
    }

    private Map<String, Object> searchText(Map<String, Object> input) throws IOException {
        String query = stringVal(input, "query", "");
        Path root = resolvePath(stringVal(input, "path", "."));
        int maxMatches = Math.min(Math.max(intVal(input.get("maxMatches"), 50), 1), 100);
        List<Map<String, Object>> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 8)) {
            List<Path> candidates = stream.filter(Files::isRegularFile)
                    .limit(Math.max(1, harnessProperties.getWorkspace().getMaxSearchFiles()))
                    .toList();
            for (Path path : candidates) {
                // 已收集到足够的匹配项，停止遍历候选文件列表，
                // 避免继续打开又跳过其余每个文件。
                if (matches.size() >= maxMatches) {
                    break;
                }
                if (Files.size(path) > 1_000_000) {
                    continue;
                }
                List<String> lines;
                try {
                    lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    continue;
                }
                for (int i = 0; i < lines.size() && matches.size() < maxMatches; i++) {
                    String line = lines.get(i);
                    if (line.contains(query)) {
                        matches.add(Map.of(
                                "path", relative(path),
                                "lineNumber", i + 1,
                                "line", line
                        ));
                    }
                }
            }
        }
        return Map.of("query", query, "matches", matches);
    }

    private Map<String, Object> proposePatch(Map<String, Object> input) throws IOException {
        Path path = resolvePath(stringVal(input, "path", ""));
        String content = stringVal(input, "content", "");
        String patch = stringVal(input, "patch", "");
        // 与 readFile 限制读取大小的方式相同，限制所提议文件的大小。
        // 若无此限制，LLM 驱动的 Agent 调用 workspace_propose_patch
        // 可能提交数 MB 的 content / patch，导致工作线程分配同等大小的
        // 字符串，随后再由 applyPatch 写入该文件。
        int maxFileBytes = Math.max(1, harnessProperties.getWorkspace().getMaxFileBytes());
        if (content.getBytes(StandardCharsets.UTF_8).length > maxFileBytes) {
            return Map.of("status", "error", "message",
                    "content exceeds maxFileBytes (" + maxFileBytes + ")");
        }
        if (patch.getBytes(StandardCharsets.UTF_8).length > maxFileBytes) {
            return Map.of("status", "error", "message",
                    "patch exceeds maxFileBytes (" + maxFileBytes + ")");
        }
        if (Files.exists(path) && Files.isRegularFile(path)
                && Files.size(path) > maxFileBytes) {
            return Map.of("status", "error", "message",
                    "existing file exceeds maxFileBytes (" + maxFileBytes + ")");
        }
        String oldContent = Files.exists(path) && Files.isRegularFile(path)
                ? Files.readString(path, StandardCharsets.UTF_8)
                : "";
        String diff = StringUtils.hasText(patch)
                ? patch
                : diffService.create(relative(path), oldContent, content);
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("path", relative(path));
        proposal.put("summary", stringVal(input, "summary", ""));
        proposal.put("contentBytes", content.getBytes(StandardCharsets.UTF_8).length);
        proposal.put("patch", diff);
        proposal.put("wouldCreate", !Files.exists(path));
        proposal.put("applyAction", "workspace_apply_patch");
        return proposal;
    }

    private Map<String, Object> applyPatch(Map<String, Object> input) throws IOException {
        if (!harnessProperties.getWorkspace().isWriteEnabled()) {
            return Map.of("status", "error", "message", "workspace writes are disabled");
        }
        Path path = resolvePath(stringVal(input, "path", ""));
        String content = stringVal(input, "content", "");
        String patch = stringVal(input, "patch", "");
        // 与 proposePatch 相同的限制，防止调用方直接走 apply
        // 绕过预览阶段的容量上限。
        int maxFileBytes = Math.max(1, harnessProperties.getWorkspace().getMaxFileBytes());
        if (content.getBytes(StandardCharsets.UTF_8).length > maxFileBytes) {
            return Map.of("status", "error", "message",
                    "content exceeds maxFileBytes (" + maxFileBytes + ")");
        }
        if (patch.getBytes(StandardCharsets.UTF_8).length > maxFileBytes) {
            return Map.of("status", "error", "message",
                    "patch exceeds maxFileBytes (" + maxFileBytes + ")");
        }
        String nextContent = content;
        if (StringUtils.hasText(patch)) {
            String oldContent = Files.exists(path) && Files.isRegularFile(path)
                    ? Files.readString(path, StandardCharsets.UTF_8)
                    : "";
            nextContent = diffService.apply(oldContent, patch);
        }
        if (nextContent.getBytes(StandardCharsets.UTF_8).length > maxFileBytes) {
            return Map.of("status", "error", "message",
                    "resulting content exceeds maxFileBytes (" + maxFileBytes + ")");
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, nextContent, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return Map.of(
                "status", "written",
                "path", relative(path),
                "bytes", nextContent.getBytes(StandardCharsets.UTF_8).length
        );
    }

    private Map<String, Object> runCommand(Map<String, Object> input) throws IOException, InterruptedException {
        if (!harnessProperties.getWorkspace().isShellEnabled()) {
            return Map.of("status", "error", "message", "workspace shell is disabled");
        }
        List<String> command = tokenizeCommand(stringVal(input, "command", ""));
        if (!isAllowedCommand(command)) {
            return Map.of("status", "error", "message", "command is not allowed");
        }
        int defaultTimeout = Math.max(1, harnessProperties.getWorkspace().getCommandTimeoutSeconds());
        int timeoutSeconds = Math.min(Math.max(intVal(input.get("timeoutSeconds"), defaultTimeout), 1), 30);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspaceRoot.toFile());
        Process process = builder.start();
        try {
            CompletableFuture<ProcessOutput> stdout = CompletableFuture.supplyAsync(
                    () -> readProcessOutput(process.getInputStream()));
            CompletableFuture<ProcessOutput> stderr = CompletableFuture.supplyAsync(
                    () -> readProcessOutput(process.getErrorStream()));
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                return Map.of("status", "error", "message", "command timed out");
            }
            ProcessOutput stdoutOutput = stdout.join();
            ProcessOutput stderrOutput = stderr.join();
            String stdoutText = new String(stdoutOutput.bytes(), StandardCharsets.UTF_8);
            String stderrText = new String(stderrOutput.bytes(), StandardCharsets.UTF_8);
            return Map.of(
                    "exitCode", process.exitValue(),
                    "stdout", stdoutText,
                    "stderr", stderrText,
                    "output", stdoutText + stderrText,
                    "truncated", stdoutOutput.truncated() || stderrOutput.truncated()
            );
        } finally {
            // 对已退出的进程是无操作；确保即使 waitFor 被中断子进程
            // 也能被回收，不遗留句柄。
            process.destroyForcibly();
        }
    }

    private Map<String, Object> fileSummary(Path path) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", relative(path));
        result.put("type", Files.isDirectory(path) ? "directory" : "file");
        if (Files.isRegularFile(path)) {
            try {
                result.put("size", Files.size(path));
            } catch (IOException ignored) {
                result.put("size", null);
            }
        }
        return result;
    }

    /** 沙箱核心：解析后 normalize，必须仍以 workspace 根为前缀，否则视为路径逃逸直接拒绝 */
    private Path resolvePath(String raw) {
        Path resolved = workspaceRoot.resolve(StringUtils.hasText(raw) ? raw : ".").normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("path escapes workspace root");
        }
        return resolved;
    }

    private String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(workspaceRoot)) {
            return ".";
        }
        return workspaceRoot.relativize(normalized).toString();
    }

    private byte[] readPrefix(Path path, int maxBytes) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return input.readNBytes(maxBytes);
        }
    }

    private ProcessOutput readProcessOutput(InputStream stream) {
        try (var input = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            input.transferTo(buffer);
            byte[] raw = buffer.toByteArray();
            int maxOutputBytes = maxCommandOutputBytes();
            if (raw.length <= maxOutputBytes) {
                return new ProcessOutput(raw, false);
            }
            return new ProcessOutput(Arrays.copyOf(raw, maxOutputBytes), true);
        } catch (IOException ex) {
            return new ProcessOutput(("failed to read process output: " + ex.getMessage())
                    .getBytes(StandardCharsets.UTF_8), false);
        }
    }

    private List<String> tokenizeCommand(String command) {
        if (!StringUtils.hasText(command)) {
            return List.of();
        }
        return Stream.of(command.trim().split("\\s+")).filter(StringUtils::hasText).toList();
    }

    private boolean isAllowedCommand(List<String> command) {
        if (command.isEmpty()) {
            return false;
        }
        String executable = command.get(0);
        if (!harnessProperties.getWorkspace().getAllowedCommands().contains(executable)) {
            return false;
        }
        if ("pwd".equals(executable)) {
            return command.size() == 1;
        }
        if ("ls".equals(executable) || "rg".equals(executable)) {
            // 非 flag 参数视为文件系统路径（对 rg 来说第一个非 flag
            // 参数是匹配模式，最坏也只是匹配不到结果）；拒绝任何解析后
            // 落在 workspace 根目录之外的参数，防止借 shell 读取宿主机文件。
            return argsWithinWorkspace(command.subList(1, command.size()));
        }
        if ("git".equals(executable)) {
            return command.size() >= 2
                    && harnessProperties.getWorkspace().getAllowedGitSubcommands().contains(command.get(1))
                    && argsAreSafeGitFlags(command.subList(2, command.size()));
        }
        if ("mvn".equals(executable)) {
            return command.stream().anyMatch("test"::equals)
                    && command.stream().allMatch(this::isAllowedMvnTestToken);
        }
        return false;
    }

    /**
     * mvn test 允许列表：-q，以及属性名以已知安全前缀开头的
     * -D&lt;name&gt;=&lt;value&gt;。若不做此限制，被提示词注入或配置错误的
     * LLM 驱动 Agent 在调用 workspace_run_shell 时可能传入
     *   mvn test -DargLine="-javaagent:/tmp/evil.jar"
     *   mvn test -Dsurefire.suiteXmlFiles=/tmp/evil.xml
     * 使 Surefire 测试 JVM 加载恶意 Java agent 或执行自定义
     * suite XML，从测试进程内部绕过 workspace 沙箱。
     */
    private static final java.util.Set<String> SAFE_MVN_PROPERTY_PREFIXES = java.util.Set.of(
            // 用户确实会想要传入的 Surefire 测试选择参数。其余参数
            // （argLine、exec.executable、surefire.suiteXmlFiles、
            // maven.compiler 等）可能影响测试 JVM 的类路径或执行，
            // 一律拒绝。
            "test=",
            "groups=",
            "excludedGroups=",
            "failIfNoTests=",
            "skipTests=",
            "maven.test.skip="
    );

    private boolean isAllowedMvnTestToken(String token) {
        if ("mvn".equals(token) || "test".equals(token) || "-q".equals(token)) {
            return true;
        }
        if (token.startsWith("-D")) {
            String property = token.substring(2);
            for (String prefix : SAFE_MVN_PROPERTY_PREFIXES) {
                if (property.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * git flag 拒绝列表：传给白名单内 git 子命令的每个 flag 都不得命中
     * 此处所列项。主要风险是 --output，它会让 git log / git show
     * 写入宿主机路径，破坏 workspace 沙箱。--exec / --upload-pack /
     * --receive-pack 会接受攻击者可控的命令，同样予以拒绝。
     */
    private static final java.util.Set<String> UNSAFE_GIT_FLAGS = java.util.Set.of(
            "--output", "-o", "--exec", "--upload-pack", "--receive-pack",
            "--ssh-command");

    private boolean argsAreSafeGitFlags(java.util.List<String> args) {
        return args.stream().filter(a -> a.startsWith("-"))
                .map(this::stripOptionValue)
                .noneMatch(UNSAFE_GIT_FLAGS::contains);
    }

    private String stripOptionValue(String arg) {
        int eq = arg.indexOf('=');
        return eq < 0 ? arg : arg.substring(0, eq);
    }

    private boolean argsWithinWorkspace(List<String> args) {
        for (String arg : args) {
            if (arg.startsWith("-")) {
                // 拒绝会导致底层工具执行命令或读取宿主机任意文件的选项。
                // ripgrep 的 --pre 和 --pre-glob 会在搜索每个文件前执行
                // shell 命令；--hostname-bin 和 --regexp-file 会读取宿主机
                // 文件系统中的文件。ls / git 目前没有同类选项，因此采用
                // 列举选项的方式让允许列表保持收紧。
                String normalized = stripOptionValue(arg);
                if (normalized.startsWith("--pre")
                        || normalized.startsWith("--pre-glob")
                        || normalized.equals("--hostname-bin")
                        || normalized.equals("--regexp-file")) {
                    return false;
                }
                continue;
            }
            try {
                Path resolved = workspaceRoot.resolve(arg).normalize();
                if (!resolved.startsWith(workspaceRoot)) {
                    return false;
                }
            } catch (Exception ex) {
                return false;
            }
        }
        return true;
    }

    private int maxCommandOutputBytes() {
        return Math.max(1, harnessProperties.getWorkspace().getMaxCommandOutputBytes());
    }

    private String stringVal(Map<String, Object> input, String key, String fallback) {
        Object raw = input.get(key);
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw).trim();
        return StringUtils.hasText(value) ? value : fallback;
    }

    private int intVal(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long elapsedMs(long startedNs) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
    }

    private static AgentHarnessProperties defaultProperties(Path workspaceRoot) {
        AgentHarnessProperties properties = new AgentHarnessProperties();
        properties.getWorkspace().setRoot(workspaceRoot.toString());
        properties.getWorkspace().setWriteEnabled(true);
        properties.getWorkspace().setShellEnabled(true);
        return properties;
    }

    private record ProcessOutput(byte[] bytes, boolean truncated) {
    }
}
