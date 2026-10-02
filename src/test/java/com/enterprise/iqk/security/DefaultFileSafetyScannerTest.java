package com.enterprise.iqk.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultFileSafetyScannerTest {

    private final DefaultFileSafetyScanner scanner = new DefaultFileSafetyScanner();

    @Test
    void shouldAcceptPdfMagicHeader() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "doc.pdf",
                "application/pdf",
                "%PDF-1.7\ncontent".getBytes()
        );

        assertDoesNotThrow(() -> scanner.scan(file));
    }

    @Test
    void shouldRejectRenamedNonPdf() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "doc.pdf",
                "application/pdf",
                "plain text".getBytes()
        );

        assertThrows(IllegalArgumentException.class, () -> scanner.scan(file));
    }

    @Test
    void shouldRejectEicarSignature() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "doc.pdf",
                "application/pdf",
                "%PDF-1.7\nEICAR-STANDARD-ANTIVIRUS-TEST-FILE".getBytes()
        );

        assertThrows(IllegalArgumentException.class, () -> scanner.scan(file));
    }

    @Test
    void shouldAcceptDocWithOle2Magic() {
        byte[] ole2 = {
            (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1,
            0x00, 0x00
        };
        MockMultipartFile file = new MockMultipartFile("file", "doc.doc", "application/msword", ole2);

        assertDoesNotThrow(() -> scanner.scan(file));
    }

    @Test
    void shouldAcceptDocxWithZipMagic() {
        byte[] zip = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00};
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", zip
        );

        assertDoesNotThrow(() -> scanner.scan(file));
    }

    @Test
    void shouldAcceptMarkdownWithoutMagic() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.md", "text/markdown", "# 标题\n正文内容".getBytes()
        );

        assertDoesNotThrow(() -> scanner.scan(file));
    }

    @Test
    void shouldRejectDisallowedExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "script.txt", "text/plain", "hello".getBytes());

        assertThrows(IllegalArgumentException.class, () -> scanner.scan(file));
    }

    @Test
    void shouldRejectExtensionlessFile() {
        MockMultipartFile file = new MockMultipartFile("file", "README", "text/plain", "hello".getBytes());

        assertThrows(IllegalArgumentException.class, () -> scanner.scan(file));
    }

    @Test
    void shouldRejectFakeDocWithWrongMagic() {
        MockMultipartFile file = new MockMultipartFile("file", "doc.doc", "application/msword", "not ole2".getBytes());

        assertThrows(IllegalArgumentException.class, () -> scanner.scan(file));
    }

    @Test
    void shouldRejectFakeDocxWithWrongMagic() {
        MockMultipartFile file = new MockMultipartFile("file", "doc.docx", "application/zip", "not a zip".getBytes());

        assertThrows(IllegalArgumentException.class, () -> scanner.scan(file));
    }

    @Test
    void shouldRejectEicarSignatureInMarkdown() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.md", "text/markdown", "# 笔记\nEICAR-STANDARD-ANTIVIRUS-TEST-FILE".getBytes()
        );

        assertThrows(IllegalArgumentException.class, () -> scanner.scan(file));
    }
}
