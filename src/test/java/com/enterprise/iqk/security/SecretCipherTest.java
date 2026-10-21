package com.enterprise.iqk.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 凭据字段级加密（FR-R12-01）：AES-256-GCM 往返与防篡改、ENC1 信封幂等、
 * 无密钥透传模式、字段级变换只动敏感字段、存量明文扫描。
 */
class SecretCipherTest {

    private static final byte[] KEY_BYTES = new byte[32];

    static {
        new java.security.SecureRandom().nextBytes(KEY_BYTES);
    }

    private SecretCipher cipherWithKey() {
        return new SecretCipher(Base64.getEncoder().encodeToString(KEY_BYTES), new ObjectMapper());
    }

    private SecretCipher cipherNoKey() {
        return new SecretCipher("", new ObjectMapper());
    }

    @Test
    void encryptDecryptRoundTrip() {
        SecretCipher cipher = cipherWithKey();
        String encrypted = cipher.encrypt("sk-secret-123");

        assertThat(encrypted).startsWith("ENC1:").doesNotContain("sk-secret-123");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("sk-secret-123");
        assertThat(cipher.isEncrypted(encrypted)).isTrue();
    }

    @Test
    void encryptIsIdempotentForEnvelopeValues() {
        SecretCipher cipher = cipherWithKey();
        String first = cipher.encrypt("sk-secret-123");

        assertThat(cipher.encrypt(first)).isEqualTo(first);
    }

    @Test
    void tamperedCiphertextFailsClosed() {
        SecretCipher cipher = cipherWithKey();
        String encrypted = cipher.encrypt("sk-secret-123");
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "AAAA";

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getReason()).contains("解密失败"));
    }

    @Test
    void wrongKeyFailsDecryption() {
        byte[] otherKey = new byte[32];
        new java.security.SecureRandom().nextBytes(otherKey);
        SecretCipher other = new SecretCipher(Base64.getEncoder().encodeToString(otherKey),
                new ObjectMapper());

        assertThatThrownBy(() -> other.decrypt(cipherWithKey().encrypt("sk-secret-123")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void noKeyPassthroughModeWithPlaintextCompat() {
        SecretCipher cipher = cipherNoKey();
        // 明文透传：兼容存量明文读取
        assertThat(cipher.encrypt("plain")).isEqualTo("plain");
        assertThat(cipher.decrypt("plain")).isEqualTo("plain");
        // 但检测到 ENC1 信封时 fail-closed：无密钥解不开
        assertThatThrownBy(() -> cipher.decrypt("ENC1:iv:data"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void transformFieldsOnlyTouchesSecretNamedFields() throws Exception {
        SecretCipher cipher = cipherWithKey();
        String config = """
                {"apiKey":"sk-live-9","apiUrl":"https://api.local","modelName":"qwen-plus",
                 "nested":{"password":"db-pass","split":{"chunkSize":100}},
                 "tokens":[{"password":"inner-pass"}]}""";

        JsonNode encrypted = cipher.transformFields(new ObjectMapper().readTree(config), true);

        // 只动命中敏感词表的字段；apiUrl/modelName/chunkSize 原样
        assertThat(encrypted.path("apiKey").asText()).startsWith("ENC1:");
        assertThat(encrypted.path("apiUrl").asText()).isEqualTo("https://api.local");
        assertThat(encrypted.path("modelName").asText()).isEqualTo("qwen-plus");
        assertThat(encrypted.path("nested").path("password").asText()).startsWith("ENC1:");
        assertThat(encrypted.path("nested").path("split").path("chunkSize").asInt()).isEqualTo(100);
        assertThat(encrypted.path("tokens").get(0).path("password").asText()).startsWith("ENC1:");

        JsonNode decrypted = cipher.transformFields(encrypted, false);
        assertThat(decrypted.path("apiKey").asText()).isEqualTo("sk-live-9");
        assertThat(decrypted.path("nested").path("password").asText()).isEqualTo("db-pass");
        assertThat(decrypted.path("tokens").get(0).path("password").asText()).isEqualTo("inner-pass");
    }

    @Test
    void containsPlainSecretDetectsLegacyPlaintext() throws Exception {
        SecretCipher cipher = cipherWithKey();
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("apiKey", "sk-live");
        Map<String, Object> encryptedConfig = new LinkedHashMap<>();
        encryptedConfig.put("apiKey", cipher.encrypt("sk-live"));

        assertThat(cipher.containsPlainSecret(
                new ObjectMapper().writeValueAsString(plain))).isTrue();
        assertThat(cipher.containsPlainSecret(
                new ObjectMapper().writeValueAsString(encryptedConfig))).isFalse();
    }

    @Test
    void malformedKeyFailsFastAtConstruction() {
        assertThatThrownBy(() -> new SecretCipher("not-base64-key", new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class);
    }
}
