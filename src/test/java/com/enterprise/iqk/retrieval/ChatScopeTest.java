package com.enterprise.iqk.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 会话软作用域契约：chat_id 不再硬过滤，同会话命中的文档获得有界加分（+0.05，封顶 1.0）。
 */
class ChatScopeTest {

    @Test
    void sameChatDocumentIsRecognized() {
        Document doc = Document.builder().text("内容").metadata(Map.of("chat_id", "chat-1")).build();

        assertThat(ChatScope.isSameChat(doc, "chat-1")).isTrue();
        assertThat(ChatScope.isSameChat(doc, "chat-2")).isFalse();
    }

    @Test
    void blankChatIdOrMissingMetadataNeverBoosts() {
        Document doc = Document.builder().text("内容").metadata(Map.of("chat_id", "chat-1")).build();
        Document noMeta = Document.builder().text("内容").build();

        assertThat(ChatScope.isSameChat(doc, null)).isFalse();
        assertThat(ChatScope.isSameChat(doc, "  ")).isFalse();
        assertThat(ChatScope.isSameChat(noMeta, "chat-1")).isFalse();
    }

    @Test
    void boostAddsBoundedAmountAndCapsAtOne() {
        Document same = Document.builder().text("内容").metadata(Map.of("chat_id", "chat-1")).build();
        Document other = Document.builder().text("内容").metadata(Map.of("chat_id", "chat-9")).build();

        assertThat(ChatScope.boost(0.80, same, "chat-1")).isCloseTo(0.85, within(1e-9));
        assertThat(ChatScope.boost(0.80, other, "chat-1")).isEqualTo(0.80);
        // 已接近满分时封顶 1.0，不产生超出量纲的分数
        assertThat(ChatScope.boost(0.99, same, "chat-1")).isEqualTo(1.0);
    }
}
