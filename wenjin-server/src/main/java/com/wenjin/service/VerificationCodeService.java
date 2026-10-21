package com.wenjin.service;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.mail.EmailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 注册邮箱验证码：内存存储、一次性消费、60 秒冷却、5 分钟过期。
 */
@Service
public class VerificationCodeService {

    static final Duration TTL = Duration.ofMinutes(5);
    static final Duration COOLDOWN = Duration.ofSeconds(60);

    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final EmailSender emailSender;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    public VerificationCodeService(EmailSender emailSender, Clock clock) {
        this.emailSender = emailSender;
        this.clock = clock;
    }

    public void send(String rawEmail) {
        String email = normalize(rawEmail);
        if (!emailSender.available()) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "邮件服务未配置");
        }
        Instant now = clock.instant();
        Entry prev = store.get(email);
        if (prev != null && Duration.between(prev.lastSentAt(), now).compareTo(COOLDOWN) < 0) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "验证码发送过于频繁，请稍后再试");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        emailSender.send(
                email,
                "问津注册验证码",
                "你的注册验证码是 " + code + "，5分钟内有效。如非本人操作请忽略。");
        store.put(email, new Entry(code, now.plus(TTL), now));
    }

    public void consume(String rawEmail, String code) {
        String email = normalize(rawEmail);
        if (code == null || !code.matches("\\d{6}")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "验证码错误或已过期");
        }
        Instant now = clock.instant();
        Entry entry = store.get(email);
        if (entry == null || now.isAfter(entry.expiresAt()) || !entry.code().equals(code)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "验证码错误或已过期");
        }
        store.remove(email, entry);
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "邮箱不能为空");
        }
        String email = raw.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(email).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "邮箱格式不正确");
        }
        return email;
    }

    private record Entry(String code, Instant expiresAt, Instant lastSentAt) {
    }
}
