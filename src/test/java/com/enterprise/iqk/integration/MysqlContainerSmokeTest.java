package com.enterprise.iqk.integration;

import com.enterprise.iqk.domain.agent.AgentBindingRow;
import com.enterprise.iqk.domain.agent.AgentBindingTable;
import com.enterprise.iqk.domain.agent.AgentInvocationLogRecord;
import com.enterprise.iqk.domain.agent.AgentRuntimeConfig;
import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.domain.vo.AgentCatalogItemVO;
import com.enterprise.iqk.llm.PlatformChatModelRegistry;
import com.enterprise.iqk.mapper.AgentBindingMapper;
import com.enterprise.iqk.mapper.AgentInvocationLogMapper;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.enterprise.iqk.service.agent.AgentCatalogService;
import com.enterprise.iqk.service.agent.AgentRuntimeConfigService;
import com.enterprise.iqk.service.platform.PlatformAssetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=dev",
                "spring.ai.openai.api-key=test-key",
                "app.ingestion.worker-enabled=false",
                "app.ingestion.queue-backend=db_polling",
                "app.vector-store.backend=simple",
                "app.vector-store.require-pgvector=false",
                "management.health.redis.enabled=false",
                "management.health.rabbit.enabled=false",
                "management.tracing.enabled=false"
        }
)
class MysqlContainerSmokeTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("enterprise_agent_platform")
            .withUsername("root")
            .withPassword("root");

    /** V25 存量迁移的验证种子：JSON 里故意带 -1 哨兵与 executionConfirm=false，逐项断言迁移行为。 */
    private static final String LEGACY_AGENT_CONFIG = """
            {"prompt":"旧版存量智能体","modelId":990002,"executionConfirm":false,
             "workflowIds":[-1],"safetyGuardIds":[990003,990004],"knowledgeBaseIds":[990005],
             "tools":[{"id":-1,"description":"哨兵"}],"publishChannels":["web","api"]}""";

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        // 容器此时已启动、Flyway 尚未执行（上下文刷新晚于本方法）——唯一能插存量数据再让迁移处理的时点
        seedLegacyAgentAsset();
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static void seedLegacyAgentAsset() {
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             var statement = connection.prepareStatement("""
                     INSERT INTO platform_asset
                       (id, tenant_id, asset_type, name, status, config_json, created_by, created_at, updated_at)
                     VALUES (990001, 'public', 'agents', 'legacy-migration-agent', 'PUBLISHED', ?, 'smoke', NOW(), NOW())
                     """)) {
            statement.setString(1, LEGACY_AGENT_CONFIG);
            statement.executeUpdate();
        } catch (Exception ex) {
            throw new IllegalStateException("存量智能体种子数据插入失败", ex);
        }
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void startsApplicationAndAppliesAllFlywayMigrations() {
        assertNotNull(flyway.info().current());
        // 与 db/migration 下最高迁移版本保持一致（当前 V27），新增迁移时同步更新
        assertEquals("27", flyway.info().current().getVersion().getVersion());

        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().get("status"));
    }

    @Test
    void platformAssetListQueriesExecuteAgainstRealMysql(@Autowired PlatformAssetService platformAssetService) {
        platformAssetService.create("agents", "smoke-agent", "回归测试用", "{\"prompt\":\"hi\"}", null, "smoke");

        // 不带搜索：验证 LIMIT/OFFSET 占位符能在 MySQL 驱动下正常绑定
        List<PlatformAsset> withoutSearch = platformAssetService.list("agents", null, 1, 20);
        assertFalse(withoutSearch.isEmpty());

        // 带搜索：LIKE 的 ESCAPE 子句曾因文本块转义错误吞掉 LIMIT/OFFSET 占位符，导致线上 500，此处防回归
        List<PlatformAsset> withSearch = platformAssetService.list("agents", "smoke", 1, 20);
        assertEquals(1, withSearch.size());
        assertEquals(1, platformAssetService.count("agents", "smoke"));
    }

    /** V25 JSON_TABLE 存量迁移在真实 MySQL 8.4 上可执行且行为符合预期（R1.3 存量数据搬进关系表）。 */
    @Test
    void legacyJsonBindingsMigrateIntoBindingTables(@Autowired JdbcTemplate jdbcTemplate) {
        List<Map<String, Object>> modelRows = jdbcTemplate.queryForList(
                "SELECT resource_id, execution_confirm FROM agent_model_binding WHERE tenant_id = 'public' AND agent_id = 990001");
        assertEquals(1, modelRows.size());
        assertEquals(990002L, ((Number) modelRows.get(0).get("resource_id")).longValue());
        // 顶层 executionConfirm=false 必须落成 0
        assertEquals(0, ((Number) modelRows.get(0).get("execution_confirm")).intValue());

        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_safety_binding WHERE tenant_id = 'public' AND agent_id = 990001", Integer.class));
        assertEquals(990005L, jdbcTemplate.queryForObject(
                "SELECT resource_id FROM agent_knowledge_binding WHERE tenant_id = 'public' AND agent_id = 990001", Long.class));
        List<String> channels = jdbcTemplate.queryForList(
                "SELECT channel FROM agent_publish_channel WHERE tenant_id = 'public' AND agent_id = 990001 ORDER BY binding_order",
                String.class);
        assertEquals(List.of("web", "api"), channels);

        // 旧前端 -1 哨兵：workflowIds/tools[].id 里的负数必须被过滤，不落表
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_workflow_binding WHERE tenant_id = 'public' AND agent_id = 990001", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_tool_binding WHERE tenant_id = 'public' AND agent_id = 990001", Integer.class));
    }

    /** 建资产→绑定同步→发布→运行时配置→目录→模型注册表→调用日志，全链路跑通真实 MySQL。 */
    @Test
    void agentRuntimeBindingEndToEndWorksAgainstRealMysql(
            @Autowired PlatformAssetService assetService,
            @Autowired AgentBindingMapper bindingMapper,
            @Autowired AgentRuntimeConfigService configService,
            @Autowired AgentCatalogService catalogService,
            @Autowired PlatformChatModelRegistry modelRegistry,
            @Autowired AgentInvocationLogMapper invocationLogMapper,
            @Autowired ObjectMapper objectMapper) throws Exception {
        PlatformAsset modelAsset = assetService.create("model-services", "binding-e2e-model", "回归冒烟模型服务",
                "{\"modelName\":\"qwen-smoke\",\"apiUrl\":\"http://127.0.0.1:9/v1\",\"apiKey\":\"sk-smoke-test\"}",
                null, "smoke");
        PlatformAsset guardAsset = assetService.create("safety-guards", "binding-e2e-guard", "回归冒烟安全防护",
                "{\"rules\":[{\"type\":\"KEYWORD\",\"patterns\":[\"敏感词\"]}]}", null, "smoke");
        String agentConfig = """
                {"prompt":"你是绑定链路冒烟智能体","memoryTurns":3,"modelParams":{"temperature":0.3},
                 "openingMessage":"你好，我是冒烟测试智能体","openingQuestions":["问题一","问题二"],
                 "modelId":%d,"safetyGuardIds":[%d],"publishChannels":["web"]}
                """.formatted(modelAsset.getId(), guardAsset.getId());
        PlatformAsset agent = assetService.create("agents", "binding-e2e-agent", "绑定链路回归冒烟",
                agentConfig, null, "smoke");

        // create() 钩子把 config_json 绑定 ID 同步进关系表
        List<AgentBindingRow> modelBindings = bindingMapper.findByAgent(
                AgentBindingTable.MODEL.tableName(), "public", agent.getId());
        assertEquals(1, modelBindings.size());
        assertEquals(modelAsset.getId(), modelBindings.get(0).getResourceId());
        assertEquals(List.of(guardAsset.getId()),
                bindingMapper.findByAgent(AgentBindingTable.SAFETY.tableName(), "public", agent.getId())
                        .stream().map(AgentBindingRow::getResourceId).toList());
        assertEquals(List.of("web"), bindingMapper.findByAgent(
                        AgentBindingTable.PUBLISH_CHANNEL.tableName(), "public", agent.getId())
                .stream().map(AgentBindingRow::getChannel).toList());

        // 草稿态：普通用户 403，调试模式可加载（绑定模型/标量配置都从真实表读出）
        authenticateAs("smoke-user", "ROLE_USER");
        try {
            ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
                    () -> configService.loadForChat("public", agent.getId(), false));
            assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());

            AgentRuntimeConfig draftConfig = configService.loadForChat("public", agent.getId(), true);
            assertEquals(agent.getId(), draftConfig.agentId());
            assertEquals("DRAFT", draftConfig.status());
            assertEquals("你是绑定链路冒烟智能体", draftConfig.prompt());
            assertEquals(0.3, draftConfig.temperature());
            assertEquals(3, draftConfig.memoryTurns());
            assertEquals("你好，我是冒烟测试智能体", draftConfig.openingMessage());
            assertEquals(List.of("问题一", "问题二"), draftConfig.openingQuestions());
            assertTrue(draftConfig.hasModel());
            assertEquals(modelAsset.getId(), draftConfig.modelServiceId());
            assertEquals("qwen-smoke", draftConfig.boundModelName());
            assertEquals(List.of(guardAsset.getId()), draftConfig.safetyGuardIds());
        } finally {
            SecurityContextHolder.clearContext();
        }

        // 发布后渠道表重写为 web+api，普通用户可正常加载
        PlatformAsset published = assetService.publishAgent(agent.getId(), List.of("web", "api"));
        assertEquals("PUBLISHED", published.getStatus());
        assertEquals(List.of("web", "api"), bindingMapper.findByAgent(
                        AgentBindingTable.PUBLISH_CHANNEL.tableName(), "public", agent.getId())
                .stream().map(AgentBindingRow::getChannel).toList());
        authenticateAs("smoke-user", "ROLE_USER");
        AgentRuntimeConfig liveConfig;
        try {
            liveConfig = configService.loadForChat("public", agent.getId(), false);
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertEquals("PUBLISHED", liveConfig.status());
        assertEquals(List.of("web", "api"), liveConfig.publishChannels());

        // 目录只透出展示字段，序列化结果不得含 prompt/模型/防护键
        AgentCatalogItemVO catalogItem = catalogService.listUsable("public").stream()
                .filter(item -> agent.getId().equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("已发布智能体不在目录中"));
        assertEquals("binding-e2e-agent", catalogItem.getName());
        assertEquals("你好，我是冒烟测试智能体", catalogItem.getOpeningMessage());
        assertEquals(List.of("问题一", "问题二"), catalogItem.getOpeningQuestions());
        String catalogJson = objectMapper.writeValueAsString(catalogItem);
        assertFalse(catalogJson.contains("\"prompt\""));
        assertFalse(catalogJson.contains("\"modelId\""));
        assertFalse(catalogJson.contains("\"safetyGuardIds\""));

        // 注册表：同指纹两次 resolve 命中缓存同实例，evict 后重建
        PlatformChatModelRegistry.BoundChatModel first = modelRegistry.resolve(modelAsset.getId(), "public");
        assertSame(first, modelRegistry.resolve(modelAsset.getId(), "public"));
        assertEquals("qwen-smoke", first.modelName());
        modelRegistry.evict(modelAsset.getId());
        assertNotSame(first, modelRegistry.resolve(modelAsset.getId(), "public"));

        // 调用日志插读往返
        AgentInvocationLogRecord record = AgentInvocationLogRecord.builder()
                .tenantId("public").agentId(agent.getId()).userId("smoke-user").chatId("smoke-chat-e2e")
                .channel("debug").status("SUCCESS").modelName("qwen-smoke").fallbackApplied(false)
                .latencyMs(42L).promptExcerpt("你好").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        invocationLogMapper.insert(record);
        AgentInvocationLogRecord loaded = invocationLogMapper.selectById(record.getId());
        assertNotNull(loaded);
        assertEquals(agent.getId(), loaded.getAgentId());
        assertEquals("SUCCESS", loaded.getStatus());
        assertEquals("qwen-smoke", loaded.getModelName());
        assertFalse(loaded.getFallbackApplied());
    }

    /** V26 系统共享资产（scope=SYSTEM）在真实 MySQL 上的跨租户可见性：联合列表 + 运行时解析 + 越权隔离。 */
    @Test
    void systemScopedAssetsAreVisibleAcrossTenants(
            @Autowired PlatformAssetService assetService,
            @Autowired PlatformAssetMapper assetMapper) {
        authenticateAs("smoke-admin", "ROLE_ADMIN");
        PlatformAsset systemModel;
        PlatformAsset tenantModel;
        try {
            // 系统共享行：落 public 主租户
            systemModel = assetService.create("model-services", "system-shared-model", null,
                    "{\"modelName\":\"qwen-system\",\"apiUrl\":\"http://127.0.0.1:9/v1\",\"apiKey\":\"sk-system\"}",
                    null, "smoke", "SYSTEM");
            assertEquals("SYSTEM", systemModel.getScope());
            assertEquals("public", systemModel.getTenantId());

            // 业务资产带 SYSTEM 直接拒绝
            ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                    () -> assetService.create("agents", "no-system-agent", null, "{}", null, "smoke", "SYSTEM"));
            assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        } finally {
            SecurityContextHolder.clearContext();
        }

        // 另一租户视角（tenant_id=u-smoke）：联合列表能看到系统行；本租户私有行互不可见
        tenantModel = insertTenantPrivateModel(assetMapper);
        List<PlatformAsset> visible = listAsTenant("u-smoke", assetService);
        assertTrue(visible.stream().anyMatch(a -> a.getId().equals(systemModel.getId())),
                "系统共享模型必须对其它租户可见");
        assertTrue(visible.stream().anyMatch(a -> a.getId().equals(tenantModel.getId())),
                "本租户私有模型可见");

        // 运行时联合解析：u-smoke 能按 ID 解析系统行；public 租户看不到 u-smoke 的私有行
        assertNotNull(assetMapper.findByIdAndTypeVisible(systemModel.getId(), "u-smoke", "model-services"));
        assertNull(assetMapper.findByIdAndTypeVisible(tenantModel.getId(), "public", "model-services"));

        // 脱敏对系统行同样生效（跨租户读不泄露明文密钥）
        assertTrue(visible.stream().filter(a -> a.getId().equals(systemModel.getId()))
                .allMatch(a -> a.getConfigJson().contains("******")));
    }

    private List<PlatformAsset> listAsTenant(String tenantId, PlatformAssetService assetService) {
        try {
            org.slf4j.MDC.put("tenant_id", tenantId);
            return assetService.list("model-services", null, 1, 50);
        } finally {
            org.slf4j.MDC.remove("tenant_id");
        }
    }

    private PlatformAsset insertTenantPrivateModel(PlatformAssetMapper assetMapper) {
        PlatformAsset asset = PlatformAsset.builder()
                .tenantId("u-smoke").assetType("model-services").scope("TENANT").name("u-smoke-private-model")
                .status("UNTESTED")
                .configJson("{\"modelName\":\"qwen-private\",\"apiUrl\":\"http://127.0.0.1:9/v1\",\"apiKey\":\"sk-private\"}")
                .createdBy("smoke").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        assetMapper.insert(asset);
        return asset;
    }

    private static void authenticateAs(String user, String... authorities) {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(user, "n/a", authorities));
    }
}
