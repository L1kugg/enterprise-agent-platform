package com.enterprise.iqk.service.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

/**
 * 代码式工具沙箱（CODE_API，FR-R7-02）：GraalJS 社区版内嵌执行，脚本只拥有"纯计算"能力——
 * allowHostAccess(NONE)/allowIO(NONE)/allowCreateProcess(NONE)/allowEnvironmentAccess(NONE)
 * 联合关闭 Java 类、文件系统、进程与环境变量通道；语句数上限（防无限循环）+ 超时中断
 * （Context.interrupt，独立调度器触发）防资源耗尽；返回值经 JSON.stringify 回收并截断，
 * 不把 polyglot 对象泄漏给宿主。
 * 执行契约：参数 Map 序列化注入为 JS 全局 `input`；用户代码包装成函数体执行——
 * 代码内可 `return 结果`，也可给 `output` 赋值（两种写法都收）。
 * 仅支持 JavaScript；凭据不注入（需要上游数据的用 FORM_API，代码只做纯变换）。
 */
@Slf4j
@Service
public class CodeToolSandboxService {

    /** 支持的语言白名单（前端表单同款小写归一）。 */
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("javascript", "js");
    /** 语句数上限：防无界循环把线程钉死（正常工具逻辑远用不到这个量级）。 */
    private static final long STATEMENT_LIMIT = 200_000L;
    /** 返回值截断上限：与 FORM_API 响应摘要同口径（观测层还有 4000 字符的二次消毒）。 */
    private static final int MAX_OUTPUT_CHARS = 16 * 1024;

    private final ObjectMapper objectMapper;
    /** 超时中断调度器：daemon 线程，只做 context.interrupt，不执行脚本。 */
    private final ScheduledExecutorService interruptScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "code-tool-sandbox-watchdog");
                thread.setDaemon(true);
                return thread;
            });

    public CodeToolSandboxService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 按工具 config 执行一段 JS：返回 {status:"sandbox", output:<结果>, elapsedMs}。 */
    public Map<String, Object> execute(JsonNode config, Map<String, Object> params) throws Exception {
        String language = config.path("language").asText("JAVASCRIPT").trim().toLowerCase();
        if (!SUPPORTED_LANGUAGES.contains(language)) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "代码式工具暂只支持 JavaScript 沙箱，当前语言：" + language);
        }
        String code = config.path("code").asText("");
        if (code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具代码不能为空");
        }
        long timeoutMs = clampTimeout(config.path("timeoutMs").asInt(3000));

        long startedNanos = System.nanoTime();
        try (Context context = newContext()) {
            Value bindings = context.getBindings("js");
            bindings.putMember("input", context.eval("js",
                    "(" + objectMapper.writeValueAsString(params == null ? Map.of() : params) + ")"));

            ScheduledFuture<?> watchdog = interruptScheduler.schedule(
                    () -> context.close(true), timeoutMs, TimeUnit.MILLISECONDS);
            Value result;
            try {
                result = context.eval("js", wrapped(code));
            } catch (PolyglotException ex) {
                watchdog.cancel(false);
                throw translate(ex, timeoutMs);
            }
            watchdog.cancel(false);

            String output = result == null || result.isNull()
                    ? "null"
                    : stringify(context, result);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "sandbox");
            payload.put("output", truncate(output));
            payload.put("elapsedMs", ToolInvocationService.elapsedMs(startedNanos));
            return payload;
        }
    }

    /**
     * 沙箱 Context：所有对外通道显式关闭；未列出的 allow* 默认也是关（fail-closed），
     * 这里逐项显式声明是为了把"沙箱允诺"写在代码里而不是依赖默认值。
     * resourceLimits 的语句数上限防无限循环（超限抛 PolyglotException.isResourceExhausted）。
     */
    private Context newContext() {
        return Context.newBuilder("js")
                .allowHostAccess(org.graalvm.polyglot.HostAccess.NONE)
                .allowHostClassLookup(className -> false)
                .allowIO(org.graalvm.polyglot.io.IOAccess.NONE)
                .allowCreateProcess(false)
                .allowNativeAccess(false)
                .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
                .allowAllAccess(false)
                .resourceLimits(ResourceLimits.newBuilder()
                        .statementLimit(STATEMENT_LIMIT, null).build())
                .build();
    }

    /** 把用户代码包成函数体：内部 `return` 可直接用；未 return 时回退读 `output` 变量。 */
    private String wrapped(String code) {
        return "(function(){\n" + code
                + "\n;return (typeof output !== \"undefined\") ? output : undefined;\n})()";
    }

    /** 结果经 JSON.stringify 回收（纯 JS 通道，不触碰宿主映射），再转 Java 字符串。 */
    private String stringify(Context context, Value result) {
        try {
            Value toJson = context.eval("js", "(function(v){ return JSON.stringify(v); })");
            return toJson.execute(result).asString();
        } catch (Exception ex) {
            // 极端情况（返回不可序列化对象）：降级为 toString 截断，不让结果序列化失败炸掉整次调用
            log.warn("CODE_API 结果 JSON 序列化失败，降级 toString: {}", ex.toString());
            String text = String.valueOf(result);
            return text.length() > 2000 ? text.substring(0, 2000) : text;
        }
    }

    /** 超时/语句超限/普通脚本错误归一为可修复文案，不泄漏内部堆栈。 */
    private RuntimeException translate(PolyglotException ex, long timeoutMs) {
        if (ex.isCancelled() || (ex.getMessage() != null && ex.getMessage().contains("interrupted"))) {
            return new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT,
                    "代码执行超过 " + timeoutMs + "ms 上限，已中断");
        }
        if (ex.isResourceExhausted()) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "代码执行超出语句数限制（疑似死循环），已中止");
        }
        if (ex.isHostException()) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, "代码访问了沙箱未开放的资源");
        }
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "代码执行出错：" + firstLine(ex.getMessage()));
    }

    private String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "脚本异常";
        }
        String line = message.split("\n", 2)[0];
        return line.length() > 300 ? line.substring(0, 300) : line;
    }

    private String truncate(String value) {
        return value.length() > MAX_OUTPUT_CHARS ? value.substring(0, MAX_OUTPUT_CHARS) + "...[truncated]" : value;
    }

    private static long clampTimeout(int value) {
        return Math.max(200, Math.min(value, 10_000));
    }

    @PreDestroy
    void shutdown() {
        interruptScheduler.shutdownNow();
    }
}
