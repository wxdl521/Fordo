package com.wenjin.service;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.mail.RecordingEmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerificationCodeServiceTest {

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    private RecordingEmailSender sender;
    private MutableClock clock;
    private VerificationCodeService service;

    @BeforeEach
    void setUp() {
        sender = new RecordingEmailSender();
        clock = new MutableClock(Instant.parse("2026-08-20T07:00:00Z"));
        service = new VerificationCodeService(sender, clock);
    }

    @Test
    void send_emailsSixDigitCode_andConsumeSucceeds() {
        service.send("Alice@Example.com");

        assertThat(sender.recipients).containsExactly("alice@example.com");
        String code = extractCode(sender.bodies.get(0));
        assertThat(code).hasSize(6);
        service.consume("alice@example.com", code);
    }

    @Test
    void consume_wrongCode_throws() {
        service.send("a@b.com");
        assertThatThrownBy(() -> service.consume("a@b.com", "000000"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("验证码");
    }

    @Test
    void consume_expiredCode_throws() {
        service.send("a@b.com");
        String code = extractCode(sender.bodies.get(0));
        clock.advance(Duration.ofMinutes(6));
        assertThatThrownBy(() -> service.consume("a@b.com", code))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("验证码");
    }

    @Test
    void send_withinCooldown_throwsTooMany() {
        service.send("a@b.com");
        clock.advance(Duration.ofSeconds(30));
        assertThatThrownBy(() -> service.send("a@b.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ResultCode.TOO_MANY_REQUESTS.getCode());
    }

    @Test
    void send_afterCooldown_allowsResend_andOldCodeInvalid() {
        service.send("a@b.com");
        String first = extractCode(sender.bodies.get(0));
        clock.advance(Duration.ofSeconds(61));
        service.send("a@b.com");
        String second = extractCode(sender.bodies.get(1));
        assertThat(second).isNotEqualTo(first);
        assertThatThrownBy(() -> service.consume("a@b.com", first))
                .isInstanceOf(BusinessException.class);
        service.consume("a@b.com", second);
    }

    @Test
    void send_whenMailUnavailable_throwsServiceUnavailable() {
        sender.available = false;
        assertThatThrownBy(() -> service.send("a@b.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
        assertThat(sender.bodies).isEmpty();
    }

    @Test
    void send_invalidEmail_throwsBadRequest() {
        assertThatThrownBy(() -> service.send("not-an-email"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ResultCode.BAD_REQUEST.getCode());
    }

    @Test
    void consume_isOneTime() {
        service.send("a@b.com");
        String code = extractCode(sender.bodies.get(0));
        service.consume("a@b.com", code);
        assertThatThrownBy(() -> service.consume("a@b.com", code))
                .isInstanceOf(BusinessException.class);
    }

    private static String extractCode(String body) {
        Matcher m = CODE.matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
