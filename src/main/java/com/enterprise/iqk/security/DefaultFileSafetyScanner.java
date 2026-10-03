package com.enterprise.iqk.security;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

@Component
public class DefaultFileSafetyScanner implements FileSafetyScanner {
    private static final int SCAN_BYTES = 8192;
    /** 允许上传的后缀白名单；新增格式必须同步 IngestionService 的解析分发。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".pdf", ".doc", ".docx", ".md");
    /** OLE2（老版 .doc）文件头魔数。 */
    private static final byte[] OLE2_MAGIC = {
        (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
        (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1
    };
    /** ZIP（.docx 实为 OOXML 包）文件头魔数。 */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    @Override
    public void scan(MultipartFile file) {
        String originalName = file.getOriginalFilename();
        String filename = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        String extension = extensionOf(filename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("不支持的文件类型，仅允许 pdf、doc、docx、md");
        }
        try {
            byte[] head = readHead(file);
            checkMagic(extension, head);
            String body = new String(head, StandardCharsets.ISO_8859_1);
            if (StringUtils.hasText(body) && body.contains("EICAR-STANDARD-ANTIVIRUS-TEST-FILE")) {
                throw new IllegalArgumentException("文件检测到风险特征，已拒绝上传");
            }
        } catch (IOException e) {
            throw new IllegalStateException("file scan failed", e);
        }
    }

    /** 取 ".xxx" 后缀（含点、小写）；无后缀返回空串。 */
    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot);
    }

    /** 按后缀校验文件头魔数：PDF/DOC/DOCX 有明确魔数，.md 纯文本无魔数跳过。 */
    private void checkMagic(String extension, byte[] head) {
        switch (extension) {
            case ".pdf" -> {
                if (!new String(head, StandardCharsets.ISO_8859_1).startsWith("%PDF-")) {
                    throw new IllegalArgumentException("文件内容不是有效的 pdf，请勿改名伪造");
                }
            }
            case ".doc" -> {
                if (!startsWith(head, OLE2_MAGIC)) {
                    throw new IllegalArgumentException("文件内容不是有效的 doc，请勿改名伪造");
                }
            }
            case ".docx" -> {
                if (!startsWith(head, ZIP_MAGIC)) {
                    throw new IllegalArgumentException("文件内容不是有效的 docx，请勿改名伪造");
                }
            }
            default -> {
                // .md：纯文本，无魔数可校验
            }
        }
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private byte[] readHead(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            if (inputStream == null) {
                throw new IOException("input stream is null");
            }
            return inputStream.readNBytes(SCAN_BYTES);
        }
    }
}
