package com.wenjin.security;

import org.junit.jupiter.api.Test;

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
}
