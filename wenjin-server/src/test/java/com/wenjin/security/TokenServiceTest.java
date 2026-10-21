package com.wenjin.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private final TokenService svc = new TokenService("test-secret-key", 3600);

    @Test
    void issueThenVerify_roundTrip() {
        String token = svc.issue(2L, 2);
        Optional<TokenService.Claims> c = svc.verify(token);
        assertThat(c).isPresent();
        assertThat(c.get().uid()).isEqualTo(2L);
        assertThat(c.get().role()).isEqualTo(2);
        assertThat(c.get().exp()).isGreaterThan(System.currentTimeMillis() / 1000);
    }

    @Test
    void tamperedPayload_rejected() {
        String token = svc.issue(2L, 2);
        // 篡改 payload 第一个字符
        String tampered = (token.charAt(0) == 'A' ? 'B' : 'A') + token.substring(1);
        assertThat(svc.verify(tampered)).isEmpty();
    }

    @Test
    void wrongSecret_rejected() {
        String token = svc.issue(2L, 2);
        TokenService other = new TokenService("another-secret", 3600);
        assertThat(other.verify(token)).isEmpty();
    }

    @Test
    void expired_rejected() {
        TokenService past = new TokenService("test-secret-key", -10); // exp 落在过去
        String token = past.issue(2L, 2);
        assertThat(past.verify(token)).isEmpty();
    }

    @Test
    void malformed_rejected() {
        assertThat(svc.verify(null)).isEmpty();
        assertThat(svc.verify("")).isEmpty();
        assertThat(svc.verify("nodothere")).isEmpty();
        assertThat(svc.verify("only.")).isEmpty();
        assertThat(svc.verify(".only")).isEmpty();
    }

    @Test
    void issue_embedsIssuedAtInMillis() {
        long before = System.currentTimeMillis();
        String token = svc.issue(2L, 2);
        long after = System.currentTimeMillis();

        TokenService.Claims c = svc.verify(token).orElseThrow();

        assertThat(c.iatMs()).isBetween(before, after);
        assertThat(c.exp()).isEqualTo(c.iatMs() / 1000 + 3600);
    }

    @Test
    void legacyTokenWithoutIat_stillVerifies_withIatZero() {
        long exp = System.currentTimeMillis() / 1000 + 3600;
        String legacy = signedToken("test-secret-key",
                "{\"uid\":2,\"role\":2,\"exp\":" + exp + "}");

        Optional<TokenService.Claims> c = svc.verify(legacy);

        assertThat(c).isPresent();
        assertThat(c.get().uid()).isEqualTo(2L);
        assertThat(c.get().iatMs()).isZero();
    }

    @Test
    void threeArgClaims_defaultsIatToZero() {
        assertThat(new TokenService.Claims(1L, 2, 99L).iatMs()).isZero();
    }

    /** 手工按"payloadB64.sigB64"格式签一枚令牌，用来模拟升级前签发的旧令牌。 */
    private static String signedToken(String secret, String payloadJson) {
        try {
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            String payloadB64 = b64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return payloadB64 + "." + b64.encodeToString(mac.doFinal(payloadB64.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
