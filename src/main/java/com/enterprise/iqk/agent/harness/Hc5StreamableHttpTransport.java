package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 标准 MCP Streamable HTTP 客户端传输（自研 HC5 实现，官方 SDK 协议层的传输底座）。
 *
 * <p>协议行为对齐 MCP Streamable HTTP 规范与 SDK 自带的
 * {@code HttpClientStreamableHttpTransport}：POST 单端点、{@code initialize} 握手后
 * 按响应头 {@code Mcp-Session-Id} 维持会话、请求头带
 * {@code Accept: application/json, text/event-stream} 与协商协议版本、纯 JSON 与
 * SSE 帧两种响应格式均可解析、notification 回 202/空体。服务端 GET 流（server 主动
 * 推送）按规范属可选项，本实现不打开——harness 场景只有请求-响应式工具调用。
 *
 * <p>不直接用 SDK 自带传输的原因：其基于 JDK HttpClient，无法在连接点做 DNS 固定
 * （pom 注释有载）。本传输继续用 Apache HttpClient 5，完整保留原 SSRF 纵深：
 * 仅 http(s)、allowed-hosts 白名单不豁免地址检查、危险地址须按"主机+端口+网段"
 * 显式授权、连接点 {@link McpSsrfGuard.PinningDnsResolver} 权威校验、重定向禁用、
 * 2 MiB 响应上限（SSE 按 2 MiB 累计）、瞬时故障（IOException/5xx）线性退避重试
 * （重试不经过模型、不消耗 token）。
 *
 * <p>线程模型：由 {@link StandardMcpToolAdapter} 为每个 server 建一个传输实例，
 * 多线程共享；会话 ID 与协商版本 volatile，HTTP 客户端线程安全。
 */
@Slf4j
final class Hc5StreamableHttpTransport implements McpClientTransport {

    private static final String APPLICATION_JSON = "application/json";
    private static final String TEXT_EVENT_STREAM = "text/event-stream";
    private static final String SESSION_ID_HEADER = "Mcp-Session-Id";
    private static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

    /** 限制 MCP HTTP 响应体大小（含 SSE 累计），防恶意/被攻陷 server 用超大载荷耗尽 JVM 内存。 */
    private static final long MAX_MCP_RESPONSE_BYTES = 2L * 1024L * 1024L;

    private final AgentHarnessProperties.Mcp mcpConfig;
    private final JacksonMcpJsonMapper jsonMapper;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String endpoint;
    private final int defaultTimeoutMs;
    private final Map<String, Integer> toolTimeoutMs;
    private final CloseableHttpClient httpClient;

    /** connect() 时由客户端注入的入站消息处理器：收到的 JSON-RPC 消息经它回流（按 id 关联 pending 请求）。 */
    private final AtomicReference<Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>> handler =
            new AtomicReference<>();
    private final AtomicReference<Consumer<Throwable>> exceptionHandler = new AtomicReference<>();

    /** initialize 响应带回的会话 ID；无会话 server（部分实现）保持 null。 */
    private volatile String sessionId;
    /** initialize 响应协商出的协议版本，后续请求以请求头回传（2025-06-18 规范起要求）。 */
    private volatile String negotiatedProtocolVersion;

    Hc5StreamableHttpTransport(String baseUrl,
                               String endpoint,
                               AgentHarnessProperties harnessProperties,
                               ObjectMapper objectMapper,
                               int defaultTimeoutMs,
                               Map<String, Integer> toolTimeoutMs) {
        this.mcpConfig = harnessProperties.getMcp();
        this.objectMapper = objectMapper;
        this.jsonMapper = new JacksonMcpJsonMapper(objectMapper);
        this.baseUrl = baseUrl;
        this.endpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.toolTimeoutMs = toolTimeoutMs == null ? Map.of() : toolTimeoutMs;
        // 连接点 DNS 固定：解析出的地址即实际连接地址，消除"校验时公共 IP、连接时被改判"的重绑定窗口。
        this.httpClient = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(new McpSsrfGuard.PinningDnsResolver(mcpConfig::getAllowedInternalAddresses))
                        .build())
                // 关闭 HC5 内建重试：瞬时故障重试统一由 sendWithRetry 按配置计数与退避
                .setRetryStrategy(new DefaultHttpRequestRetryStrategy(0, TimeValue.ZERO_MILLISECONDS))
                // 重定向显式禁用：跟随跳转会让请求脱离已校验的 host/地址；若未来要支持必须对每跳重新 SSRF 校验
                .setDefaultRequestConfig(RequestConfig.custom().setRedirectsEnabled(false).build())
                .build();
    }

    /** HTTP 传输无预连接：握手（initialize）作为第一条普通消息经 sendMessage 发出。 */
    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> requestHandler) {
        this.handler.set(Objects.requireNonNull(requestHandler, "requestHandler must not be null"));
        return Mono.empty();
    }

    /** 与官方 SDK 传输默认一致地声明全部已发布协议版本（接口默认仅 2024-11-05，会让客户端按最老版本协商）。 */
    @Override
    public List<String> protocolVersions() {
        return List.of(io.modelcontextprotocol.spec.ProtocolVersions.MCP_2024_11_05,
                io.modelcontextprotocol.spec.ProtocolVersions.MCP_2025_03_26,
                io.modelcontextprotocol.spec.ProtocolVersions.MCP_2025_06_18,
                io.modelcontextprotocol.spec.ProtocolVersions.MCP_2025_11_25);
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> exceptionHandler) {
        this.exceptionHandler.set(exceptionHandler);
    }

    /**
     * 投递一条 JSON-RPC 消息：POST 单端点，响应（纯 JSON 或 SSE 帧）反序列化后经
     * connect() 注入的 handler 回流给客户端按 id 关联。返回的 Mono 表示"投递完成"。
     */
    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.fromRunnable(() -> {
            try {
                deliver(message);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt(); // 恢复中断标记，交由上层决定收尾方式
                throw new McpTransportException("mcp send interrupted", ex);
            } catch (Exception ex) {
                throw ex instanceof McpTransportException mte ? mte : new McpTransportException(
                        "mcp send failed: " + ex.getMessage(), ex);
            }
        });
    }

    private void deliver(McpSchema.JSONRPCMessage message) throws IOException, HttpException, InterruptedException {
        if (!McpSsrfGuard.isSafeBaseUrl(baseUrl, mcpConfig)) {
            throw new McpTransportException("MCP baseUrl is not a permitted public endpoint: " + baseUrl);
        }
        String jsonBody = jsonMapper.writeValueAsString(message);
        URI uri = resolveUri();
        // 每消息超时：tools/call 按工具配置，其余（initialize/tools/list/通知）用默认值
        int timeoutMs = resolveTimeout(message);
        McpSsrfGuard.enterContext(uri);
        try (CloseableHttpResponse response = sendWithRetry(uri, jsonBody, timeoutMs)) {
            String newSessionId = response.getHeader(SESSION_ID_HEADER) != null
                    ? response.getHeader(SESSION_ID_HEADER).getValue() : null;
            if (newSessionId != null && !newSessionId.isBlank()) {
                this.sessionId = newSessionId;
            }
            int status = response.getCode();
            if (status < 200 || status >= 300) {
                throw new McpTransportException("mcp http status: " + status);
            }
            String contentType = contentTypeOf(response);
            if (contentType.contains(TEXT_EVENT_STREAM)) {
                consumeSseResponse(response, message);
                return;
            }
            // 202 Accepted / 空体（notification 的合规响应）：投递完成即结束
            if (status == 202 || contentType.isBlank() || response.getEntity() == null) {
                return;
            }
            byte[] bodyBytes = readBounded(response.getEntity(), MAX_MCP_RESPONSE_BYTES);
            if (bodyBytes.length == 0) {
                return;
            }
            if (message instanceof McpSchema.JSONRPCNotification) {
                log.warn("MCP notification 收到非合规响应体（已忽略）: server={}", baseUrl);
                return;
            }
            String bodyText = new String(bodyBytes, StandardCharsets.UTF_8);
            dispatch(McpSchema.deserializeJsonRpcMessage(jsonMapper, bodyText));
            captureNegotiatedVersion(message, bodyText);
        } finally {
            McpSsrfGuard.clearContext();
        }
    }

    /** SSE 帧响应：逐帧解析 event:message 的 data 为 JSON-RPC 消息回流；收到带 id 的响应帧即终止读取（POST 响应流已交付完毕）。 */
    private void consumeSseResponse(CloseableHttpResponse response, McpSchema.JSONRPCMessage sentMessage)
            throws IOException, HttpException, InterruptedException {
        if (response.getEntity() == null) {
            return;
        }
        long total = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                response.getEntity().getContent(), StandardCharsets.UTF_8))) {
            String eventName = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                total += line.length() + 1;
                if (total > MAX_MCP_RESPONSE_BYTES) {
                    throw new ResponseTooLargeException(MAX_MCP_RESPONSE_BYTES);
                }
                if (line.isBlank()) {
                    if (!data.isEmpty() && "message".equals(eventName)) {
                        McpSchema.JSONRPCMessage parsed =
                                McpSchema.deserializeJsonRpcMessage(jsonMapper, data.toString());
                        boolean terminal = parsed instanceof McpSchema.JSONRPCResponse rpc
                                && rpc.id() != null;
                        captureNegotiatedVersion(sentMessage, data.toString());
                        dispatch(parsed);
                        if (terminal) {
                            return; // 停止读取：请求-响应式 POST 流已交付响应，不陪 server 挂长连接
                        }
                    }
                    eventName = null;
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n'); // 多行 data 按规范以换行拼接
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
        }
        if (sentMessage instanceof McpSchema.JSONRPCRequest) {
            // 流正常结束但未见带 id 的响应帧：交由客户端侧请求超时兜底，这里不猜错因
            log.warn("MCP SSE 响应流结束但未收到响应帧: server={}", baseUrl);
        }
    }

    /** 把收到的消息回流给客户端（connect 注入的 handler），由其按 JSON-RPC id 关联 pending 请求。 */
    private void dispatch(McpSchema.JSONRPCMessage message) {
        Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> inbound = handler.get();
        if (inbound == null) {
            throw new McpTransportException("transport not connected");
        }
        Mono<McpSchema.JSONRPCMessage> processed = inbound.apply(Mono.just(message));
        if (processed != null) {
            processed.subscribe(r -> {
            }, ex -> {
                Consumer<Throwable> eh = exceptionHandler.get();
                if (eh != null) {
                    eh.accept(ex);
                } else {
                    log.warn("MCP 入站消息处理失败: {}", ex.getMessage());
                }
            });
        }
    }

    /** initialize 响应：从原始响应体捕获协商协议版本，后续请求以请求头回传。 */
    private void captureNegotiatedVersion(McpSchema.JSONRPCMessage sentMessage, String bodyText) {
        if (sentMessage instanceof McpSchema.JSONRPCRequest request
                && McpSchema.METHOD_INITIALIZE.equals(request.method())) {
            try {
                JsonNode root = objectMapper.readTree(bodyText);
                String version = root.path("result").path("protocolVersion").asText(null);
                if (version != null && !version.isBlank()) {
                    this.negotiatedProtocolVersion = version;
                }
            } catch (IOException ex) {
                log.warn("解析 initialize 响应协议版本失败: {}", ex.getMessage());
            }
        }
    }

    /** 每消息超时：tools/call 优先取该工具配置，其余用默认。 */
    private int resolveTimeout(McpSchema.JSONRPCMessage message) {
        if (message instanceof McpSchema.JSONRPCRequest request
                && McpSchema.METHOD_TOOLS_CALL.equals(request.method())
                && request.params() instanceof Map<?, ?> params
                && params.get("name") instanceof String toolName) {
            return toolTimeoutMs.getOrDefault(toolName, defaultTimeoutMs);
        }
        return defaultTimeoutMs;
    }

    /** 拼 baseUrl + endpoint（拼接前再过一次 SSRF 预检，防配置运行期被改；连接点另有权威校验）。 */
    private URI resolveUri() {
        String safeBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(safeBase + endpoint);
    }

    /**
     * 发送请求并重试瞬时故障：网络 IOException（超时/连接失败）与 5xx 视为可重试，
     * 4xx 与其余异常原样失败。退避按重试序号线性递增。每次尝试从 (uri, body) 重建
     * 请求（HC5 经典请求实体不宜复用，且会话头可能在上一轮响应中建立）。
     * 重试全部发生在本层，成功后模型无感知，不消耗任何模型 token（与原自研桥接语义一致）。
     */
    private CloseableHttpResponse sendWithRetry(URI uri, String jsonBody, int timeoutMs)
            throws IOException, HttpException, InterruptedException {
        int retries = Math.max(0, mcpConfig.getRetryAttempts());
        long backoffMs = Math.max(0, mcpConfig.getRetryBackoffMs());
        for (int attempt = 0; ; attempt++) {
            if (attempt > 0) {
                Thread.sleep(backoffMs * attempt);
            }
            CloseableHttpResponse response;
            try {
                response = httpClient.execute(buildRequest(uri, jsonBody), requestContext(timeoutMs));
            } catch (IOException ex) {
                if (attempt >= retries) {
                    throw ex;
                }
                continue;
            }
            if (!isRetryableStatus(response.getCode()) || attempt >= retries) {
                return response;
            }
            response.close(); // 丢弃可重试的 5xx 响应，连接归还连接池后再退避重试
        }
    }

    /** 组装一条 POST 请求：JSON 体 + Accept/会话/协议版本通用头。 */
    private ClassicHttpRequest buildRequest(URI uri, String jsonBody) {
        ClassicRequestBuilder builder = ClassicRequestBuilder.post(uri)
                .setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));
        decorate(builder);
        return builder.build();
    }

    /** 请求通用头：会话与协议版本（如已建立/协商）。 */
    private void decorate(ClassicRequestBuilder builder) {
        builder.setHeader("Accept", APPLICATION_JSON + ", " + TEXT_EVENT_STREAM);
        builder.setHeader("Cache-Control", "no-cache");
        if (sessionId != null) {
            builder.setHeader(SESSION_ID_HEADER, sessionId);
        }
        if (negotiatedProtocolVersion != null) {
            builder.setHeader(PROTOCOL_VERSION_HEADER, negotiatedProtocolVersion);
        }
    }

    /** 每请求配置：connect/response 超时 + 重定向禁用（覆盖连接级默认，双保险）。 */
    private static HttpClientContext requestContext(int timeoutMs) {
        HttpClientContext context = HttpClientContext.create();
        context.setRequestConfig(RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(timeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(timeoutMs))
                .setRedirectsEnabled(false)
                .build());
        return context;
    }

    private static String contentTypeOf(CloseableHttpResponse response) {
        if (response.getEntity() == null || response.getEntity().getContentType() == null) {
            return "";
        }
        return response.getEntity().getContentType().toLowerCase();
    }

    /** 有界读取响应体：超过 maxBytes 立即中断读取（避免超大响应先全量进内存再被拒）。 */
    private static byte[] readBounded(HttpEntity entity, long maxBytes) throws IOException {
        if (entity == null) {
            return new byte[0];
        }
        try (InputStream in = entity.getContent()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8 * 1024);
            byte[] buffer = new byte[8 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new ResponseTooLargeException(maxBytes);
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** 5xx 是服务端瞬时故障可重试；4xx 是确定性错误（参数/权限），重试无意义。 */
    private static boolean isRetryableStatus(int statusCode) {
        return statusCode >= 500 && statusCode < 600;
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return jsonMapper.convertValue(data, typeRef);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(this::shutdown);
    }

    @PreDestroy
    void shutdown() {
        try {
            httpClient.close();
        } catch (IOException ex) {
            log.warn("mcp http client close failed: {}", ex.getMessage());
        }
    }

    /** 响应体超限（含 SSE 累计）：确定性失败，避免瞬时故障重试路径。 */
    static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException(long maxBytes) {
            super("mcp response exceeds " + maxBytes + " bytes");
        }
    }

    /** 测试与观测用：当前会话 ID（无会话返回 null）。 */
    String currentSessionId() {
        return sessionId;
    }
}
