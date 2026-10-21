package com.wenjin.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 启动时检查 HMAC 签名密钥配置。
 *
 * <p>规则：
 * <ul>
 *   <li>非 dev/test profile：拒绝默认值、空值、UTF-8 长度小于 32 字节的密钥。</li>
 *   <li>仅当存在 profile 且全部为 dev/test 时：默认开发密钥仅 WARN，不阻断启动。</li>
 * </ul>
 * </p>
 *
 * <p>核心逻辑提取到包级静态方法 {@link #enforce(String, String[])}，
 * 纯单测可直接调用，无需 Spring 上下文。</p>
 */
@Component
public class AuthSecretGuard {

    private static final Logger log = LoggerFactory.getLogger(AuthSecretGuard.class);

    /** 生产/非开发环境最低密钥 UTF-8 字节长度。 */
    static final int MIN_SECRET_UTF8_BYTES = 32;

    /** 注入当前密钥，与 TokenService 保持同一占位符 + 同一默认值。 */
    @Value("${wenjin.auth.secret:" + TokenService.DEFAULT_DEV_SECRET + "}")
    private String secret;

    private final Environment environment;

    public AuthSecretGuard(Environment environment) {
        this.environment = environment;
    }

    /** 应用启动后执行密钥安全性检查。 */
    @PostConstruct
    public void init() {
        enforce(secret, environment.getActiveProfiles());
    }

    /**
     * 密钥安全执行逻辑（package-private，供单测直接调用）。
     *
     * @param secret         当前注入的签名密钥
     * @param activeProfiles 当前激活的 Spring profile 数组
     * @throws IllegalStateException 非 dev/test 下密钥不合规
     */
    static void enforce(String secret, String[] activeProfiles) {
        if (isDevOrTest(activeProfiles)) {
            if (TokenService.DEFAULT_DEV_SECRET.equals(secret)) {
                log.warn("*** 安全警告 *** 当前使用默认开发密钥（DEFAULT_DEV_SECRET），"
                        + "此密钥已公开在代码仓库，严禁在生产环境使用！"
                        + "生产部署请通过环境变量 WENJIN_AUTH_SECRET 设置自定义密钥。");
            }
            return;
        }
        checkStrength(secret, "WENJIN_AUTH_SECRET");
    }

    /**
     * 同一套强度规则供其它密钥复用（如系统配置加密密钥 {@code WENJIN_CONFIG_SECRET}）：
     * 非 dev/test 下拒绝空值、默认开发密钥、UTF-8 长度不足 {@value #MIN_SECRET_UTF8_BYTES} 字节；dev/test 下不检查、不告警。
     *
     * @param envVarName 报错提示里让运维去设置的环境变量名
     * @throws IllegalStateException 非 dev/test 下密钥不合规
     */
    public static void requireStrongOutsideDevTest(String secret, String[] activeProfiles, String envVarName) {
        if (isDevOrTest(activeProfiles)) {
            return;
        }
        checkStrength(secret, envVarName);
    }

    private static void checkStrength(String secret, String envVarName) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalStateException(
                    "非开发/测试环境禁止使用空签名密钥！"
                            + "请设置环境变量 " + envVarName + "（至少 32 字节 UTF-8）。");
        }
        if (TokenService.DEFAULT_DEV_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "生产环境（非 dev/test profile）禁止使用默认开发密钥！"
                            + "请设置环境变量 " + envVarName + " 为足够随机的长字符串后重新启动。");
        }
        int utf8Len = secret.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Len < MIN_SECRET_UTF8_BYTES) {
            throw new IllegalStateException(
                    "非开发/测试环境签名密钥 UTF-8 长度必须 ≥ " + MIN_SECRET_UTF8_BYTES
                            + " 字节，当前为 " + utf8Len + "。"
                            + "请设置环境变量 " + envVarName + "。");
        }
    }

    /** 存在 profile 且全部为 dev/test 时视为开发/测试，放宽密钥约束。 */
    private static boolean isDevOrTest(String[] activeProfiles) {
        return activeProfiles != null
                && activeProfiles.length > 0
                && Arrays.stream(activeProfiles)
                .allMatch(p -> "dev".equals(p) || "test".equals(p));
    }
}
