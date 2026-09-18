package com.enterprise.iqk.config;

import com.enterprise.iqk.config.properties.VectorStoreProperties;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

@Slf4j
@Configuration
public class VectorStoreConfiguration {

    @Bean
    public VectorStore vectorStore(OpenAiEmbeddingModel embeddingModel, VectorStoreProperties properties) {
        if (!"pgvector".equalsIgnoreCase(properties.getBackend())) {
            return SimpleVectorStore.builder(embeddingModel).build();
        }
        VectorStore store = tryBuildPgvectorStore(embeddingModel, properties);
        if (store != null) {
            return store;
        }
        if (properties.isRequirePgvector()) {
            throw new IllegalStateException("pgvector is required but initialization failed");
        }
        log.warn("pgvector backend requested but initialization failed, fallback to SimpleVectorStore.");
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    @SuppressWarnings("PMD.CloseResource") // 连接池由 PgVectorStore bean 持有，与应用同生命周期
    private VectorStore tryBuildPgvectorStore(OpenAiEmbeddingModel embeddingModel, VectorStoreProperties properties) {
        if (!StringUtils.hasText(properties.getPgvector().getUrl())) {
            log.warn("app.vector-store.pgvector.url is empty, skip pgvector initialization.");
            return null;
        }
        try {
            // 使用连接池而非 DriverManagerDataSource：后者每次检索都会新开一个
            // TCP 连接，在高负载下性能会急剧恶化。
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setPoolName("pgvector-pool");
            dataSource.setDriverClassName("org.postgresql.Driver");
            dataSource.setJdbcUrl(properties.getPgvector().getUrl());
            dataSource.setUsername(properties.getPgvector().getUsername());
            dataSource.setPassword(properties.getPgvector().getPassword());
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

            Class<?> pgVectorStoreClass = Class.forName("org.springframework.ai.vectorstore.pgvector.PgVectorStore");
            Method[] methods = pgVectorStoreClass.getMethods();
            Method builderMethod = null;
            for (Method method : methods) {
                if ("builder".equals(method.getName()) && method.getParameterCount() == 2) {
                    builderMethod = method;
                    break;
                }
            }
            if (builderMethod == null) {
                throw new IllegalStateException("PgVectorStore.builder(JdbcTemplate, EmbeddingModel) not found");
            }

            Object builder = builderMethod.invoke(null, jdbcTemplate, embeddingModel);
            invokeBuilderIfExists(builder, "schemaName", String.class, properties.getPgvector().getSchema());
            invokeBuilderIfExists(builder, "dimensions", int.class, properties.getPgvector().getDimensions());
            invokeBuilderIfExists(builder, "vectorTableName", String.class, properties.getPgvector().getTable());
            invokeBuilderIfExists(builder, "initializeSchema", boolean.class, true);

            Method buildMethod = builder.getClass().getMethod("build");
            return (VectorStore) buildMethod.invoke(builder);
        } catch (Exception e) {
            log.error("Failed to initialize pgvector store", e);
            return null;
        }
    }

    private void invokeBuilderIfExists(Object builder, String methodName, Class<?> type, Object value) {
        if (value == null) {
            return;
        }
        try {
            Method method = builder.getClass().getMethod(methodName, type);
            method.invoke(builder, value);
        } catch (NoSuchMethodException ignore) {
            // 保持对不同 Spring AI 版本的兼容性
        } catch (Exception e) {
            log.warn("Failed to invoke PgVectorStore builder method: {}", methodName, e);
        }
    }
}
