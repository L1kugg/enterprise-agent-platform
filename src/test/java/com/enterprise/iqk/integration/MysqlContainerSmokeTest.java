package com.enterprise.iqk.integration;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.service.platform.PlatformAssetService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void startsApplicationAndAppliesAllFlywayMigrations() {
        assertNotNull(flyway.info().current());
        // 与 db/migration 下最高迁移版本保持一致（当前 V24），新增迁移时同步更新
        assertEquals("24", flyway.info().current().getVersion().getVersion());

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
}
