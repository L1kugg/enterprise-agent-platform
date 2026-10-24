package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.security.FileSafetyScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ChatAttachmentServiceTest {

    private ChatAttachmentStore store;
    private ChatAttachmentService service;

    @BeforeEach
    void setUp() {
        store = new ChatAttachmentStore();
        service = new ChatAttachmentService(mock(FileSafetyScanner.class), store);
    }

    @Test
    void parsesTxtAndExposesAttachmentBlock() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "退货政策要点：七天无理由退货。".getBytes());
        ChatAttachmentService.ParsedAttachment parsed = service.parse("public", "u-1", file);

        assertThat(parsed.filename()).isEqualTo("notes.txt");
        assertThat(parsed.truncated()).isFalse();
        String block = service.attachmentBlock("public", parsed.attachmentId());
        assertThat(block)
                .contains("用户随本次提问提供的附件")
                .contains("notes.txt")
                .contains("七天无理由退货");
        // 无附件请求提示词零变化
        assertThat(service.attachmentBlock("public", null)).isEmpty();
        assertThat(service.attachmentBlock("public", "not-exist")).isEmpty();
    }

    @Test
    void tenantMismatchIsTreatedAsMissing() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "a.txt", "text/plain", "内容".getBytes());
        ChatAttachmentService.ParsedAttachment parsed = service.parse("public", "u-1", file);

        assertThat(service.attachmentBlock("other-tenant", parsed.attachmentId())).isEmpty();
        // 被越权尝试取用后条目已被惰性移除
        assertThat(service.attachmentBlock("public", parsed.attachmentId())).isEmpty();
    }

    @Test
    void expiredAttachmentIsInvisible() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "a.txt", "text/plain", "内容".getBytes());
        ChatAttachmentService.ParsedAttachment parsed = service.parse("public", "u-1", file);

        Field field = ChatAttachmentStore.class.getDeclaredField("store");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, ChatAttachmentStore.StoredAttachment> map =
                (ConcurrentHashMap<String, ChatAttachmentStore.StoredAttachment>) field.get(store);
        ChatAttachmentStore.StoredAttachment expired = new ChatAttachmentStore.StoredAttachment(
                "public", "u-1", "a.txt", "内容", false, Instant.now().minusSeconds(60));
        map.put(parsed.attachmentId(), expired);

        assertThat(service.attachmentBlock("public", parsed.attachmentId())).isEmpty();
    }

    @Test
    void oversizedTextIsTruncatedWithMarker() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 30_000; i++) {
            big.append('字');
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.txt", "text/plain", big.toString().getBytes());
        ChatAttachmentService.ParsedAttachment parsed = service.parse("public", "u-1", file);

        assertThat(parsed.truncated()).isTrue();
        assertThat(parsed.chars()).isEqualTo(24_000);
        assertThat(service.attachmentBlock("public", parsed.attachmentId())).contains("超长已截断");
    }

    @Test
    void unsafeFileIsRejectedByScanner() {
        FileSafetyScanner scanner = mock(FileSafetyScanner.class);
        doThrow(new IllegalArgumentException("不支持的文件类型")).when(scanner).scan(any());
        ChatAttachmentService guarded = new ChatAttachmentService(scanner, store);
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.exe", "application/octet-stream", new byte[] {1});

        assertThatThrownBy(() -> guarded.parse("public", "u-1", file))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
