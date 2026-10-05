package com.enterprise.iqk.rag;

import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.service.TenantCostService;
import com.enterprise.iqk.testutil.TestGuards;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 线上 RAG 链路本地重排的两个修复点回归：
 * 打分分母改为查询 token 数（长文档不再被稀释）+ 同会话有界加分（软作用域）。
 */
class RagAnswerServiceRerankTest {

    private RagAnswerService service() {
        return new RagAnswerService(mock(VectorStore.class), TestGuards.real(), mock(ChatClient.class),
                mock(ModelRouter.class), new RagProperties(), new SimpleMeterRegistry(),
                mock(TenantCostService.class));
    }

    @Test
    void rerankPrefersQueryHitsThenSameChatBoost() {
        RagAnswerService service = service();
        // 查询 "redis 缓存"：同会话文档命中 1/2 词，跨会话文档命中 1/2 词，最后一条 0 命中
        Document sameChatLowHit = Document.builder().text("redis 部署手册")
                .metadata(Map.of("chat_id", "chat-1")).build();
        Document otherChatLowHit = Document.builder().text("缓存 配置说明")
                .metadata(Map.of("chat_id", "chat-9")).build();
        Document sameChatNoHit = Document.builder().text("mysql 索引优化")
                .metadata(Map.of("chat_id", "chat-1")).build();

        List<Document> reranked = service.rerank("redis 缓存",
                List.of(sameChatNoHit, otherChatLowHit, sameChatLowHit), "chat-1");

        // 词面命中优先，同为 1/2 命中时同会话加分项决胜，零命中垫底
        assertThat(reranked).extracting(Document::getText)
                .containsExactly("redis 部署手册", "缓存 配置说明", "mysql 索引优化");
    }

    @Test
    void chineseQueryReranksByBigramHits() {
        RagAnswerService service = service();
        Document hit = Document.builder().text("课程预约怎么办理").build();
        Document miss = Document.builder().text("退费流程说明").build();

        List<Document> reranked = service.rerank("预约", List.of(miss, hit), "chat-1");

        // 中文查询按 bigram 命中排序（此前整句单 token，中文重排完全失效）
        assertThat(reranked).extracting(Document::getText)
                .containsExactly("课程预约怎么办理", "退费流程说明");
    }
}
