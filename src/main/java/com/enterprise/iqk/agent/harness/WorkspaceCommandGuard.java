package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 工作区命令策略：白名单校验 + 校验后加固，与执行（进程启动/输出读取）分离。
 * pwd/ls/rg 只允许本地只读形态；git 限定本地子命令与安全 flag；
 * mvn 仅允许 test 目标且默认强制注入 -o（离线），阻止测试进程联网拉依赖。
 */
class WorkspaceCommandGuard {

    private final AgentHarnessProperties harnessProperties;
    private final Path workspaceRoot;

    WorkspaceCommandGuard(AgentHarnessProperties harnessProperties, Path workspaceRoot) {
        this.harnessProperties = harnessProperties;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /**
     * 校验通过后的命令加固：mvn 在 mvnOffline=true（默认）时强制注入 -o，
     * 阻止测试进程联网解析依赖。-o 对 mvn 是 --offline 短旗标，
     * 与 git 的 -o（--output）语义无关，且注入发生在 isAllowed 校验之后。
     */
    List<String> prepare(List<String> command) {
        if ("mvn".equals(command.get(0)) && harnessProperties.getWorkspace().isMvnOffline()) {
            List<String> prepared = new ArrayList<>(command);
            prepared.add(1, "-o");
            return List.copyOf(prepared);
        }
        return command;
    }

    boolean isAllowed(List<String> command) {
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
    private static final Set<String> SAFE_MVN_PROPERTY_PREFIXES = Set.of(
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
    private static final Set<String> UNSAFE_GIT_FLAGS = Set.of(
            "--output", "-o", "--exec", "--upload-pack", "--receive-pack",
            "--ssh-command");

    private boolean argsAreSafeGitFlags(List<String> args) {
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
}
