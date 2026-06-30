package com.wenjin.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 启动时检查 HMAC 签名密钥配置。
 *
 * <p>规则：
 * <ul>
 *   <li>生产 profile（prod）+ 默认开发密钥 → 抛出 {@link IllegalStateException}，中止启动。</li>
 *   <li>非生产环境 + 默认开发密钥 → 打印 WARN 日志，正常启动（开发/单测勿阻断）。</li>
 *   <li>自定义密钥（无论哪个 profile）→ 不做任何处理。</li>
 * </ul>
 * </p>
 *
 * <p>核心逻辑提取到包级静态方法 {@link #enforce(String, String[])}，
 * 纯单测可直接调用，无需 Spring 上下文。</p>
 */
@Component
public class AuthSecretGuard {

    private static final Logger log = LoggerFactory.getLogger(AuthSecretGuard.class);

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
     * @throws IllegalStateException 生产 profile（prod）下使用了默认开发密钥
     */
    static void enforce(String secret, String[] activeProfiles) {
        if (!TokenService.DEFAULT_DEV_SECRET.equals(secret)) {
            // 自定义密钥，安全无需干预
            return;
        }
        boolean isProd = Arrays.asList(activeProfiles).contains("prod");
        if (isProd) {
            // 生产环境使用公开默认密钥属于严重安全漏洞，必须中止启动
            throw new IllegalStateException(
                    "生产环境（prod profile）禁止使用默认开发密钥！" +
                    "请设置环境变量 WENJIN_AUTH_SECRET 为足够随机的长字符串后重新启动。");
        }
        // 非生产环境：告警提示开发者，但不阻断启动
        log.warn("*** 安全警告 *** 当前使用默认开发密钥（DEFAULT_DEV_SECRET），" +
                 "此密钥已公开在代码仓库，严禁在生产环境使用！" +
                 "生产部署请通过环境变量 WENJIN_AUTH_SECRET 设置自定义密钥。");
    }
}
