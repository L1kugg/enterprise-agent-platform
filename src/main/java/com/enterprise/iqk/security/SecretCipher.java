package com.enterprise.iqk.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 资产凭据字段级加解密（FR-R12-01）：AES-256-GCM，信封格式
 * {@code ENC1:<base64(iv)>:<base64(密文+认证标签)>}——ENC1 版本前缀为将来
 * 换主密钥整体重加密留路。主密钥来自 APP_SECRET_KEY（32 字节 base64）。
 *
 * 字段级而非整包：config_json 混有提示词/切分配置等非敏感内容，只对命中
 * 敏感字段名规则（apikey/api_key/secret/password/credential/token，与响应
 * 掩码 isSecret 同一套词表）的字符串值加解密，其余字段原样。
 *
 * 兼容语义（平滑迁移）：
 *  - encrypt 对已带 ENC1: 前缀的值幂等跳过；
 *  - decrypt 对无 ENC1: 前缀的值原样返回（存量明文在迁移 Runner 跑完前照常可读）；
 *  - 主密钥未配置时进入透传模式（encrypt/decrypt 原样返回 + 启动 ERROR 告警），
 *    生产（security enabled + prod profile）由 AppStartupValidator 拒绝启动——fail-closed；
 *  - decrypt 对 ENC1 值解密失败（密钥不匹配/数据被篡改）抛 500 明确错误，不静默。
 */
@Slf4j
@Component
public class SecretCipher {

    private static final String PREFIX = "ENC1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Base64.Decoder B64_DECODER = Base64.getDecoder();
    private static final Base64.Encoder B64_ENCODER = Base64.getEncoder().withoutPadding();

    private final ObjectMapper objectMapper;
    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public SecretCipher(@Value("${app.secret-key:}") String rawKey, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.key = parseKey(rawKey);
    }

    /** 解析主密钥：未配置返回 null（透传模式）；格式非法 fail-fast 抛 IllegalStateException。 */
    private static SecretKeySpec parseKey(String rawKey) {
        if (!StringUtils.hasText(rawKey)) {
            log.warn("APP_SECRET_KEY 未配置：凭据将以明文存储且已加密数据无法解密，"
                    + "启用加密请设置 32 字节 base64 主密钥");
            return null;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(rawKey.trim());
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException("主密钥须为 32 字节 base64（当前 " + keyBytes.length + " 字节）");
            }
            return new SecretKeySpec(keyBytes, "AES");
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "APP_SECRET_KEY 格式非法（须为 32 字节 AES-256 base64）：" + ex.getMessage(), ex);
        }
    }

    /** 是否已加密（ENC1 信封）。 */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** 主密钥是否已配置（迁移 Runner 与启动校验用）；false 时加密/解密均透传。 */
    public boolean available() {
        return key != null;
    }

    /** 是否存在未加密的敏感字段（存量迁移扫描用）：只遍历命中敏感字段名的字符串值。 */
    public boolean containsPlainSecret(String configJson) {
        if (!StringUtils.hasText(configJson)) {
            return false;
        }
        try {
            return containsPlainSecret(objectMapper.readTree(configJson));
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean containsPlainSecret(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode child = field.getValue();
                if (child.isTextual() && isSecretField(field.getKey())
                        && StringUtils.hasText(child.asText()) && !isEncrypted(child.asText())) {
                    return true;
                }
                if (child.isContainerNode() && containsPlainSecret(child)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsPlainSecret(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 加密单个字符串值：幂等（已加密原样），无主密钥透传。 */
    public String encrypt(String plaintext) {
        if (!StringUtils.hasText(plaintext) || isEncrypted(plaintext)) {
            return plaintext;
        }
        if (key == null) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + B64_ENCODER.encodeToString(iv) + ":" + B64_ENCODER.encodeToString(cipherText);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException ex) {
            throw new IllegalStateException("凭据加密失败：" + ex.getMessage(), ex);
        }
    }

    /** 解密单个字符串值：无信封原样返回（兼容存量明文）；解密失败 fail-closed 抛明确错误。 */
    public String decrypt(String stored) {
        if (!StringUtils.hasText(stored) || !isEncrypted(stored)) {
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException(
                    "检测到已加密凭据但 APP_SECRET_KEY 未配置，无法解密；请配置主密钥后重启");
        }
        String[] parts = stored.substring(PREFIX.length()).split(":", 2);
        if (parts.length != 2) {
            throw new IllegalStateException("加密凭据信封格式非法");
        }
        try {
            byte[] iv = B64_DECODER.decode(parts[0]);
            byte[] cipherText = B64_DECODER.decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException
                | IllegalArgumentException ex) {
            log.error("加密凭据解密失败（主密钥不匹配或数据被篡改）");
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "加密凭据解密失败：主密钥不匹配或数据已损坏");
        }
    }

    /**
     * 遍历 JSON 树，对命中敏感字段名的字符串值做加/解密（递归对象与数组），返回原树。
     * 与响应掩码的 isSecret 字段名词表保持一致。
     */
    public JsonNode transformFields(JsonNode node, boolean encrypt) {
        if (node == null) {
            return null;
        }
        transformNode(node, encrypt);
        return node;
    }

    /** 便捷重载：解析 → 字段变换 → 序列化。非法 JSON 抛 400。 */
    public String transformFields(String configJson, boolean encrypt) {
        if (!StringUtils.hasText(configJson)) {
            return configJson;
        }
        try {
            JsonNode root = objectMapper.readTree(configJson);
            transformNode(root, encrypt);
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "config must be valid JSON", ex);
        }
    }

    private void transformNode(JsonNode node, boolean encrypt) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Map<String, JsonNode> replacements = new LinkedHashMap<>();
            node.fields().forEachRemaining(field -> {
                String name = field.getKey();
                JsonNode child = field.getValue();
                if (child.isTextual() && isSecretField(name)) {
                    String transformed = encrypt
                            ? encrypt(child.asText())
                            : decrypt(child.asText());
                    replacements.put(name, objectMapper.getNodeFactory().textNode(transformed));
                } else if (child.isContainerNode()) {
                    transformNode(child, encrypt);
                }
            });
            replacements.forEach((name, value) -> ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                    .set(name, value));
        } else if (node.isArray()) {
            node.forEach(child -> transformNode(child, encrypt));
        }
    }

    /** 敏感字段名词表（与 PlatformAssetService 响应掩码 isSecret 同规则）。 */
    static boolean isSecretField(String fieldName) {
        String normalized = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
        return normalized.contains("apikey") || normalized.contains("api_key")
                || normalized.contains("secret") || normalized.contains("password")
                || normalized.contains("credential") || normalized.contains("token");
    }
}
