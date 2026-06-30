package com.wenjin.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AuthSecretGuard} 纯单测：直接调用包级静态方法 {@code enforce}，无需 Spring 上下文。
 *
 * <p>TDD 红绿流程：先写此测试（编译即为 RED），实现 enforce 后转为 GREEN。</p>
 */
class AuthSecretGuardTest {

    /** 生产 profile + 默认密钥 → 必须抛出 IllegalStateException（阻止启动，部署门禁）。 */
    @Test
    void prod_defaultSecret_throwsIllegalState() {
        assertThatThrownBy(() ->
                AuthSecretGuard.enforce(TokenService.DEFAULT_DEV_SECRET, new String[]{"prod"}))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 无激活 profile（开发/单测环境）+ 默认密钥 → 仅告警，不抛异常，开发正常运行。 */
    @Test
    void noProfile_defaultSecret_doesNotThrow() {
        assertThatCode(() ->
                AuthSecretGuard.enforce(TokenService.DEFAULT_DEV_SECRET, new String[]{}))
                .doesNotThrowAnyException();
    }

    /** 任意 profile（含 prod）+ 自定义密钥 → 不抛异常。 */
    @Test
    void anyProfile_customSecret_doesNotThrow() {
        assertThatCode(() ->
                AuthSecretGuard.enforce("my-super-secret-key-2026", new String[]{"prod"}))
                .doesNotThrowAnyException();
    }
}
