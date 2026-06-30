package com.wenjin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * 无状态令牌服务：签发/校验两段式 HMAC 签名令牌 {@code payloadB64.signatureB64}。
 *
 * <p>载荷 {@link Claims} 仅含 uid/role/exp，base64url 无填充编码后用 HMAC-SHA256 签名；
 * 校验时常量时间比对签名并查过期。无其它 Spring 依赖，可直接 {@code new} 出来做单测。</p>
 */
@Component
public class TokenService {

    /**
     * 默认开发密钥明文（公开在仓库）。
     * {@link com.wenjin.security.AuthSecretGuard} 在生产 profile 下检测到此值时会中止启动。
     */
    public static final String DEFAULT_DEV_SECRET = "dev-secret-change-me-in-prod";

    /** 令牌载荷：用户 id、角色、过期时间（epoch 秒）。 */
    public record Claims(long uid, int role, long exp) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long ttlSeconds;

    public TokenService(@Value("${wenjin.auth.secret:" + DEFAULT_DEV_SECRET + "}") String secret,
                        @Value("${wenjin.auth.ttl-seconds:604800}") long ttlSeconds) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    /** 签发令牌：exp = now + ttl。 */
    public String issue(long uid, int role) {
        long exp = System.currentTimeMillis() / 1000 + ttlSeconds;
        String payloadJson;
        try {
            payloadJson = MAPPER.writeValueAsString(new Claims(uid, role, exp));
        } catch (Exception e) {
            throw new IllegalStateException("令牌载荷序列化失败", e);
        }
        String payloadB64 = B64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return payloadB64 + "." + sign(payloadB64);
    }

    /** 校验：签名不符/篡改/过期/格式错误一律返回空。 */
    public Optional<Claims> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        String payloadB64 = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        // 常量时间比对，避免签名比对的时序侧信道
        if (!MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8),
                sign(payloadB64).getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            Claims c = MAPPER.readValue(B64D.decode(payloadB64), Claims.class);
            if (c.exp() <= System.currentTimeMillis() / 1000) {
                return Optional.empty();
            }
            return Optional.of(c);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String sign(String payloadB64) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(payloadB64.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }
}
