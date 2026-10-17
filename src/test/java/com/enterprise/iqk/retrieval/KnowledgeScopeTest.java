package com.enterprise.iqk.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库召回作用域语义（FR-R5-01）：
 * 不限库 = 原租户级行为零变化；圈定 = 只放行命中绑定列表的切片，
 * 未归属（无元数据）一并排除——"绑定即收紧"。
 */
class KnowledgeScopeTest {

    @Test
    void unrestrictedAllowsEverythingIncludingUnownedDocuments() {
        KnowledgeScope scope = KnowledgeScope.unrestricted();
        Document withKb = Document.builder().text("a").metadata(Map.of("knowledge_base_id", 1L)).build();
        Document withoutKb = Document.builder().text("b").metadata(Map.of()).build();

        assertThat(scope.restricted()).isFalse();
        assertThat(scope.allows(withKb)).isTrue();
        assertThat(scope.allows(withoutKb)).isTrue();
    }

    @Test
    void ofNullOrCreateEmptyListFallsBackToUnrestricted() {
        assertThat(KnowledgeScope.of(null).restricted()).isFalse();
        assertThat(KnowledgeScope.of(List.of()).restricted()).isFalse();
        assertThat(KnowledgeScope.of(null)).isSameAs(KnowledgeScope.unrestricted());
    }

    @Test
    void restrictedKeepsMatchingKbAndDropsUnownedDocuments() {
        KnowledgeScope scope = KnowledgeScope.of(List.of(1L, 2L));

        assertThat(scope.restricted()).isTrue();
        assertThat(scope.allows(docWithKb(1))).isTrue();
        assertThat(scope.allows(docWithKb(2))).isTrue();
        // 未归属（历史文档/聊天输入框上传）：圈定时不漏进来
        assertThat(scope.allows(Document.builder().text("legacy").metadata(Map.of()).build())).isFalse();
        // 其他库的文档不放行
        assertThat(scope.allows(docWithKb(9))).isFalse();
    }

    @Test
    void kbIdOfAcceptsLongIntegerAndStringFormsFromMetadataRoundtrip() {
        // 写入是 Long，SimpleVectorStore JSON 快照回读可能退化为 Integer/字符串
        assertThat(KnowledgeScope.kbIdOf(docWithKb(1))).isEqualTo(1L);
        Document integerForm = Document.builder().text("a").metadata(Map.of("knowledge_base_id", 3)).build();
        assertThat(KnowledgeScope.kbIdOf(integerForm)).isEqualTo(3L);
        Document stringForm = Document.builder().text("a").metadata(Map.of("knowledge_base_id", "4")).build();
        assertThat(KnowledgeScope.kbIdOf(stringForm)).isEqualTo(4L);
        assertThat(KnowledgeScope.kbIdOf(Document.builder().text("x").metadata(Map.of()).build())).isNull();
        assertThat(KnowledgeScope.kbIdOf((Document) null)).isNull();
    }

    private Document docWithKb(long kbId) {
        return Document.builder().text("doc").metadata(Map.of("knowledge_base_id", kbId)).build();
    }
}
