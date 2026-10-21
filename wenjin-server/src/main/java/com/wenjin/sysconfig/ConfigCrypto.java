package com.wenjin.sysconfig;

import com.wenjin.security.AuthSecretGuard;
import com.wenjin.security.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * secret 类型配置的落库加解密：AES-256-GCM（仅 JDK javax.crypto）。
 *
 * <h2>密文格式</h2>
 * <p>{@code base64( IV(12 字节随机) || 密文 || GCM tag(16 字节) )}；AAD = 配置 key（UTF-8），
 * 把密文绑定到所属 key，库里互换两条密文会解密失败而不是串号。</p>
 *
 * <h2>密钥来源</h2>
 * <ol>
 *   <li>{@code wenjin.config.secret}（环境变量 {@code WENJIN_CONFIG_SECRET}）非空时用它；</li>
 *   <li>否则用 {@code wenjin.auth.secret}（令牌签名密钥）。</li>
 * </ol>
 * <p>两种来源都经 HKDF-SHA256（RFC 5869，固定 salt / info 做域分离）派生 32 字节 AES 密钥，
 * 所以即便复用签名密钥，AES 密钥与 HMAC 密钥也互不相同。</p>
 *
 * <h2>fail-fast</h2>
 * <p>非 dev/test profile 下，实际生效的那个来源复用 {@link AuthSecretGuard#requireStrongOutsideDevTest} 的规则
 * （拒绝空值、默认开发密钥、UTF-8 &lt; 32 字节），不合规直接阻断启动。</p>
 *
 * <p>换密钥后旧密文无法解密：{@link #decrypt} 抛 {@link IllegalStateException}，由调用方降级（启动时跳过该项）。</p>
 */
@Component
public class ConfigCrypto {

    private static final Logger log = LoggerFactory.getLogger(ConfigCrypto.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] HKDF_SALT = "wenjin/sys_config".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HKDF_INFO = "aes-256-gcm/v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ConfigCrypto(@Value("${wenjin.config.secret:}") String configSecret,
                        @Value("${wenjin.auth.secret:" + TokenService.DEFAULT_DEV_SECRET + "}") String authSecret,
                        Environment environment) {
        this(configSecret, authSecret, environment.getActiveProfiles());
    }

    ConfigCrypto(String configSecret, String authSecret, String[] activeProfiles) {
        boolean dedicated = configSecret != null && !configSecret.isBlank();
        String source = dedicated ? configSecret : authSecret;
        AuthSecretGuard.requireStrongOutsideDevTest(source, activeProfiles,
                dedicated ? "WENJIN_CONFIG_SECRET" : "WENJIN_CONFIG_SECRET（或 WENJIN_AUTH_SECRET）");
        if (source == null || source.isEmpty()) {
            // 仅 dev/test 可能走到这里：保持可用，但密钥退化为默认开发密钥
            source = TokenService.DEFAULT_DEV_SECRET;
        }
        log.info("系统配置加密密钥来源：{}", dedicated ? "wenjin.config.secret" : "由 wenjin.auth.secret 派生");
        this.key = new SecretKeySpec(hkdfSha256(source.getBytes(StandardCharsets.UTF_8)), "AES");
    }

    /** 加密明文，返回 base64(IV || 密文+tag)。 */
    public String encrypt(String configKey, String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(configKey.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            // 不带明文、不带底层 message
            throw new IllegalStateException("配置加密失败: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 解密；密钥不符 / 密文被改 / 格式错误一律抛 {@link IllegalStateException}（message 不含任何密文或明文）。
     */
    public String decrypt(String configKey, String ciphertext) {
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext);
            if (all.length < IV_BYTES + TAG_BITS / 8) {
                throw new IllegalArgumentException("too short");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            cipher.updateAAD(configKey.getBytes(StandardCharsets.UTF_8));
            byte[] pt = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("配置解密失败（密钥可能已更换或密文损坏）: " + e.getClass().getSimpleName());
        }
    }

    /** HKDF-SHA256：PRK = HMAC(salt, IKM)；OKM = HMAC(PRK, info || 0x01)，取 32 字节（恰好一块）。 */
    static byte[] hkdfSha256(byte[] ikm) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HKDF_SALT, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(HKDF_INFO);
            mac.update((byte) 0x01);
            byte[] okm = mac.doFinal();
            Arrays.fill(prk, (byte) 0);
            return okm;
        } catch (Exception e) {
            throw new IllegalStateException("配置密钥派生失败", e);
        }
    }
}
