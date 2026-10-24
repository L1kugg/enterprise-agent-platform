package com.enterprise.iqk.service.platform;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.enterprise.iqk.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 工作流导入/导出/上下线/复制/试运行：先提供结构校验与 START/HTTP/TOOL 可执行子集。 */
@Service
@RequiredArgsConstructor
public class PlatformWorkflowService {
    private final PlatformAssetMapper assetMapper;
    private final PlatformAssetService assetService;
    private final PlatformToolTestService toolTestService;
    private final ObjectMapper objectMapper;

    public PlatformAsset copy(Long id, String targetName) {
        PlatformAsset source = owned(id);
        String base = targetName == null || targetName.isBlank() ? source.getName() + " - 副本" : targetName.trim();
        for (int i = 2; i < 100; i++) {
            try {
                return assetService.create("workflows", i == 2 ? base : base + " (" + i + ")",
                        source.getDescription(), source.getConfigJson(), null, source.getCreatedBy());
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode() != HttpStatus.CONFLICT) throw ex;
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "无法生成唯一工作流名称");
    }

    public JsonNode export(Long id) {
        JsonNode graph = graphOf(owned(id));
        validateGraph(graph);
        return graph;
    }

    public PlatformAsset importWorkflow(String name, String description, String configJson) {
        if (name == null || name.isBlank()) throw bad("工作流名称不能为空");
        JsonNode graph = parse(configJson);
        validateGraph(graph);
        try {
            return assetService.create("workflows", name, description,
                    objectMapper.writeValueAsString(graph), null, currentCreator());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作流 JSON 序列化失败", ex);
        }
    }

    public PlatformAsset online(Long id) {
        validateGraph(graphOf(owned(id)));
        return changeStatus(id, "ONLINE");
    }

    public PlatformAsset offline(Long id) {
        return changeStatus(id, "OFFLINE");
    }

    public Map<String, Object> testRun(Long id, Map<String, Object> input) {
        PlatformAsset workflow = owned(id);
        JsonNode graph = graphOf(workflow);
        validateGraph(graph);
        List<Map<String, Object>> trace = new ArrayList<>();
        Map<String, Object> context = new LinkedHashMap<>(input == null ? Map.of() : input);
        Set<String> visited = new HashSet<>();
        JsonNode current = startNode(graph);
        int guard = 0;
        while (current != null && guard++ < graph.path("nodes").size() * 4 + 4) {
            if (!visited.add(current.path("id").asText())) {
                return failure(trace, "工作流存在循环，试运行终止");
            }
            long nodeStarted = System.nanoTime();
            String nodeId = current.path("id").asText();
            String nodeType = current.path("type").asText().toUpperCase(Locale.ROOT);
            String error = null;
            try {
                switch (nodeType) {
                    case "START" -> { }
                    case "END" -> {
                        trace.add(trace(trace, nodeId, current, "COMPLETED", nodeStarted, summarize(context), null));
                        return success(trace, context);
                    }
                    case "HTTP_REQUEST" -> executeHttp(current, context);
                    case "TOOL_CALL" -> executeTool(current, context);
                    default -> throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                            "节点类型暂不支持试运行：" + nodeType);
                }
            } catch (ResponseStatusException ex) {
                error = ex.getReason();
            } catch (Exception ex) {
                error = ex.getMessage() == null ? "节点执行失败" : ex.getMessage();
            }
            trace.add(trace(trace, nodeId, current, error == null ? "COMPLETED" : "FAILED", nodeStarted,
                    summarize(context), error));
            if (error != null) return failure(trace, error);
            current = nextNode(graph, current, context);
        }
        return failure(trace, "工作流未连接到 END 节点");
    }

    /** 图执行器复用：校验并返回工作流图（供 WorkflowGraphRuntime 运行为任务）。 */
    public JsonNode validatedGraph(PlatformAsset workflow) {
        JsonNode graph = graphOf(workflow);
        validateGraph(graph);
        return graph;
    }

    /** 图执行器复用：START 节点。 */
    public JsonNode startNodeOf(JsonNode graph) {
        return startNode(graph);
    }

    /** 图执行器复用：按边条件选下一节点。 */
    public JsonNode nextNodeAfter(JsonNode graph, JsonNode current, Map<String, Object> context) {
        return nextNode(graph, current, context);
    }

    /** 图执行器复用：HTTP_REQUEST 节点执行（与试运行同一实现）。 */
    public void executeHttpNode(JsonNode node, Map<String, Object> context) throws Exception {
        executeHttp(node, context);
    }

    /** 图执行器复用：TOOL_CALL 节点执行（与试运行同一实现，走 ToolTestService→ToolInvocationService 同链路）。 */
    public void executeToolNode(JsonNode node, Map<String, Object> context) {
        executeTool(node, context);
    }

    private void executeHttp(JsonNode node, Map<String, Object> context) throws Exception {
        JsonNode parameters = node.path("parameters");
        String urlTemplate = parameters.path("url").asText(parameters.path("apiUrl").asText());
        if (urlTemplate.isBlank()) throw bad("HTTP_REQUEST 节点缺少 url");
        String url = urlTemplate;
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            url = url.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()))
                    .replace("${" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        java.net.URI uri = java.net.URI.create(url);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw bad("HTTP_REQUEST 节点 URL 仅支持 http/https");
        }
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(clampTimeout(parameters.path("timeoutMs").asInt(5000))))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofMillis(clampTimeout(parameters.path("timeoutMs").asInt(5000))))
                .method(parameters.path("method").asText("GET").toUpperCase(Locale.ROOT),
                        java.net.http.HttpRequest.BodyPublishers.noBody())
                .build();
        java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        context.put(node.path("id").asText() + ".status", response.statusCode());
        context.put(node.path("id").asText() + ".body", truncate(response.body(), 16_384));
        if (response.statusCode() >= 400) {
            throw bad("HTTP_REQUEST 节点返回状态码 " + response.statusCode());
        }
    }

    private void executeTool(JsonNode node, Map<String, Object> context) {
        long toolId = node.path("parameters").path("toolId").asLong();
        if (toolId <= 0) throw bad("TOOL_CALL 节点缺少 toolId");
        JsonNode argumentNodes = node.path("parameters").path("params");
        Map<String, Object> params = new HashMap<>();
        argumentNodes.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            params.put(entry.getKey(), value.isContainerNode() ? value.toString() : value.asText());
        });
        Map<String, Object> result = toolTestService.test(toolId, params);
        context.put(node.path("id").asText() + ".result", result);
        Object status = result.get("status");
        if ("ERROR".equals(status)) throw bad(String.valueOf(result.get("error")));
    }

    private JsonNode nextNode(JsonNode graph, JsonNode current, Map<String, Object> context) {
        List<JsonNode> outgoing = new ArrayList<>();
        graph.path("edges").forEach(edge -> {
            if (current.path("id").asText().equals(edge.path("source").asText())) outgoing.add(edge);
        });
        JsonNode selected = outgoing.stream()
                .filter(edge -> conditionMatches(edge.path("condition").asText(null), context))
                .findFirst()
                .orElseGet(() -> outgoing.isEmpty() ? null : outgoing.get(0));
        if (selected == null) return null;
        String targetId = selected.path("target").asText();
        for (JsonNode node : graph.path("nodes")) {
            if (node.path("id").asText().equals(targetId)) return node;
        }
        return null;
    }

    private boolean conditionMatches(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank() || expression.equals("true")) return true;
        String[] parts = expression.split("==", 2);
        if (parts.length != 2) return false;
        String key = parts[0].trim();
        if (key.startsWith("input.")) key = key.substring(6);
        String expected = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
        return String.valueOf(context.get(key)).equals(expected);
    }

    private Map<String, Object> success(List<Map<String, Object>> trace, Map<String, Object> output) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCEEDED");
        result.put("output", output);
        result.put("trace", trace);
        return result;
    }

    private Map<String, Object> failure(List<Map<String, Object>> trace, String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "FAILED");
        result.put("error", error);
        result.put("trace", trace);
        return result;
    }

    private Map<String, Object> trace(List<Map<String, Object>> ignoredPrior, String nodeId, JsonNode node,
                                      String status, long startedAt, String summary, String error) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("nodeId", nodeId);
        item.put("nodeName", node.path("name").asText(nodeId));
        item.put("status", status);
        item.put("startedAt", LocalDateTime.now().withNano(0).toString());
        item.put("endedAt", LocalDateTime.now().withNano(0).toString());
        item.put("durationMs", Math.max(1, (System.nanoTime() - startedAt) / 1_000_000));
        item.put("outputSummary", summary);
        if (error != null) item.put("error", error);
        return item;
    }

    private PlatformAsset changeStatus(Long id, String status) {
        PlatformAsset workflow = owned(id);
        workflow.setStatus(status);
        workflow.setUpdatedAt(LocalDateTime.now());
        assetMapper.updateById(workflow);
        return assetService.get("workflows", id);
    }

    private JsonNode graphOf(PlatformAsset workflow) {
        JsonNode config = parse(workflow.getConfigJson());
        JsonNode graph = config.path("graph");
        return graph.isObject() ? graph : config;
    }

    private void validateGraph(JsonNode graph) {
        if (!graph.isObject() || !graph.path("nodes").isArray() || graph.path("nodes").isEmpty()) {
            throw bad("工作流缺少 nodes");
        }
        if (!graph.path("edges").isArray()) throw bad("工作流缺少 edges 数组");
        Set<String> ids = new HashSet<>();
        int startCount = 0;
        int endCount = 0;
        for (JsonNode node : graph.path("nodes")) {
            String id = node.path("id").asText();
            if (id.isBlank() || !ids.add(id)) throw bad("节点 ID 为空或重复：" + id);
            String type = node.path("type").asText().toUpperCase(Locale.ROOT);
            if ("START".equals(type)) startCount++;
            if ("END".equals(type)) endCount++;
        }
        if (startCount != 1) throw bad("工作流必须有且只有一个 START 节点");
        if (endCount == 0) throw bad("工作流至少需要一个 END 节点");
        for (JsonNode edge : graph.path("edges")) {
            if (!ids.contains(edge.path("source").asText()) || !ids.contains(edge.path("target").asText())) {
                throw bad("连线引用了不存在的节点");
            }
        }
        if (!reachableEnd(graph)) throw bad("START 节点无法到达 END 节点");
    }

    private boolean reachableEnd(JsonNode graph) {
        JsonNode start = startNode(graph);
        Set<String> visited = new HashSet<>();
        List<JsonNode> queue = new ArrayList<>(List.of(start));
        while (!queue.isEmpty()) {
            JsonNode current = queue.remove(0);
            if (!visited.add(current.path("id").asText())) continue;
            if ("END".equals(current.path("type").asText().toUpperCase(Locale.ROOT))) return true;
            graph.path("edges").forEach(edge -> {
                if (current.path("id").asText().equals(edge.path("source").asText())) {
                    graph.path("nodes").forEach(node -> {
                        if (node.path("id").asText().equals(edge.path("target").asText())) queue.add(node);
                    });
                }
            });
        }
        return false;
    }

    private JsonNode startNode(JsonNode graph) {
        for (JsonNode node : graph.path("nodes")) {
            if ("START".equals(node.path("type").asText().toUpperCase(Locale.ROOT))) return node;
        }
        throw bad("START 节点不存在");
    }

    private PlatformAsset owned(Long id) {
        PlatformAsset workflow = assetMapper.findByIdAndType(id, TenantContext.currentTenantId(), "workflows");
        if (workflow == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工作流不存在");
        return workflow;
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception ex) {
            throw bad("工作流 JSON 不合法");
        }
    }

    private String summarize(Map<String, Object> context) {
        try {
            return truncate(objectMapper.writeValueAsString(context), 4096);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static String truncate(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit) + "...[truncated]";
    }

    private static int clampTimeout(int value) {
        return Math.max(500, Math.min(value, 60_000));
    }

    private static String currentCreator() {
        return com.enterprise.iqk.security.UserContext.currentUserId(null);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
