package com.wenjin.sysconfig;

import com.wenjin.security.TokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ConfigCrypto：AES-GCM 往返、随机 IV、密文绑定 key、换密钥解不开；密钥来源与非 dev/test 下的 fail-fast。
 */
class ConfigCryptoTest {

    private static final String STRONG = "config-secret-that-is-at-least-32-bytes!";
    private static final String STRONG_AUTH = "auth-secret-that-is-at-least-32-bytes-long";
    private static final String[] DEV = {"dev"};
    private static final String[] PROD = {"prod"};
    private static final String KEY = "wenjin.ai.api-key";
    private static final String PLAIN = "sk-test-0123456789abcdef";

    @Test
    @DisplayName("加密后可解密回原文；密文是 base64(12 字节 IV + 密文 + 16 字节 tag)，不含明文")
    void roundTrip() {
        ConfigCrypto crypto = new ConfigCrypto(STRONG, STRONG_AUTH, PROD);

        String ct = crypto.encrypt(KEY, PLAIN);

        assertThat(ct).doesNotContain(PLAIN).doesNotContain("0123456789abcdef");
        assertThat(Base64.getDecoder().decode(ct)).hasSize(12 + PLAIN.length() + 16);
        assertThat(crypto.decrypt(KEY, ct)).isEqualTo(PLAIN);
    }

    @Test
    @DisplayName("每次加密用随机 IV：同一明文两次密文不同，都能解开")
    void randomIv() {
        ConfigCrypto crypto = new ConfigCrypto(STRONG, STRONG_AUTH, PROD);

        String a = crypto.encrypt(KEY, PLAIN);
        String b = crypto.encrypt(KEY, PLAIN);

        assertThat(a).isNotEqualTo(b);
        assertThat(crypto.decrypt(KEY, a)).isEqualTo(PLAIN);
        assertThat(crypto.decrypt(KEY, b)).isEqualTo(PLAIN);
    }

    @Test
    @DisplayName("密文绑定配置 key（AAD）：拿去当别的 key 解密失败")
    void ciphertextBoundToKey() {
        ConfigCrypto crypto = new ConfigCrypto(STRONG, STRONG_AUTH, PROD);
        String ct = crypto.encrypt(KEY, PLAIN);

        assertThatThrownBy(() -> crypto.decrypt("wenjin.ai.vision.api-key", ct))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("换了密钥 / 密文被改 / 非 base64：解密抛 IllegalStateException，message 不含明文或密文")
    void wrongKeyOrTampered_throwsWithoutLeaking() {
        ConfigCrypto crypto = new ConfigCrypto(STRONG, STRONG_AUTH, PROD);
        String ct = crypto.encrypt(KEY, PLAIN);
        ConfigCrypto other = new ConfigCrypto("another-config-secret-at-least-32-bytes", STRONG_AUTH, PROD);

        assertThatThrownBy(() -> other.decrypt(KEY, ct))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(PLAIN).doesNotContain(ct));

        byte[] raw = Base64.getDecoder().decode(ct);
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> crypto.decrypt(KEY, tampered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt(KEY, "%%%not-base64")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt(KEY, "AAAA")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("密钥来源：配了 WENJIN_CONFIG_SECRET 就用它（与签名密钥无关）；没配则由 wenjin.auth.secret 派生")
    void keySource() {
        ConfigCrypto dedicatedA = new ConfigCrypto(STRONG, STRONG_AUTH, PROD);
        ConfigCrypto dedicatedB = new ConfigCrypto(STRONG, "different-auth-secret-at-least-32-bytes", PROD);
        ConfigCrypto derived = new ConfigCrypto("", STRONG_AUTH, PROD);
        ConfigCrypto derivedNull = new ConfigCrypto(null, STRONG_AUTH, PROD);

        String ct = dedicatedA.encrypt(KEY, PLAIN);
        assertThat(dedicatedB.decrypt(KEY, ct)).as("同一 config secret，签名密钥不同也能解").isEqualTo(PLAIN);
        assertThatThrownBy(() -> derived.decrypt(KEY, ct)).isInstanceOf(IllegalStateException.class);

        String ct2 = derived.encrypt(KEY, PLAIN);
        assertThat(derivedNull.decrypt(KEY, ct2)).isEqualTo(PLAIN);
    }

    @Test
    @DisplayName("派生密钥与签名密钥本身不同（HKDF 域分离），且长度 32 字节")
    void hkdfDomainSeparated() {
        byte[] okm = ConfigCrypto.hkdfSha256(STRONG_AUTH.getBytes());
        assertThat(okm).hasSize(32).isNotEqualTo(STRONG_AUTH.getBytes());
        assertThat(ConfigCrypto.hkdfSha256(STRONG_AUTH.getBytes())).isEqualTo(okm);
    }

    @Test
    @DisplayName("prod：两者都没有 / 都是默认开发密钥 → 启动失败")
    void prod_defaultOrMissing_failFast() {
        assertThatThrownBy(() -> new ConfigCrypto("", TokenService.DEFAULT_DEV_SECRET, PROD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WENJIN_CONFIG_SECRET");
        assertThatThrownBy(() -> new ConfigCrypto(null, null, PROD))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ConfigCrypto(null, "short-auth-secret", PROD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("prod：显式配置了但过短的 WENJIN_CONFIG_SECRET 同样拒绝（不悄悄回落到签名密钥）")
    void prod_shortDedicatedSecret_failFast() {
        assertThatThrownBy(() -> new ConfigCrypto("short-config", STRONG_AUTH, PROD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WENJIN_CONFIG_SECRET");
    }

    @Test
    @DisplayName("prod：强 config secret 即可（签名密钥由 AuthSecretGuard 另行把关）；强签名密钥回落也可")
    void prod_strongSources_ok() {
        assertThatCode(() -> new ConfigCrypto(STRONG, TokenService.DEFAULT_DEV_SECRET, PROD)).doesNotThrowAnyException();
        assertThatCode(() -> new ConfigCrypto("", STRONG_AUTH, PROD)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("dev / test：默认开发密钥与空值都放行，仍可正常加解密")
    void devTest_relaxed() {
        ConfigCrypto dev = new ConfigCrypto("", TokenService.DEFAULT_DEV_SECRET, DEV);
        assertThat(dev.decrypt(KEY, dev.encrypt(KEY, PLAIN))).isEqualTo(PLAIN);

        ConfigCrypto test = new ConfigCrypto(null, null, new String[]{"test"});
        assertThat(test.decrypt(KEY, test.encrypt(KEY, PLAIN))).isEqualTo(PLAIN);
    }
}
