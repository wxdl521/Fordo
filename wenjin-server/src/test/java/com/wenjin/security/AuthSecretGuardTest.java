package com.wenjin.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AuthSecretGuard} 纯单测：直接调用包级静态方法 {@code enforce}，无需 Spring 上下文。
 *
 * <p>非 dev/test 拒绝默认值、空值、UTF-8 &lt; 32 字节；dev/test 放宽。</p>
 */
class AuthSecretGuardTest {

    private static final String SECRET_31 = "a".repeat(31);
    private static final String SECRET_32 = "a".repeat(32);

    /** 生产 profile + 默认密钥 → 必须抛出 IllegalStateException。 */
    @Test
    void prod_defaultSecret_throwsIllegalState() {
        assertThatThrownBy(() ->
                AuthSecretGuard.enforce(TokenService.DEFAULT_DEV_SECRET, new String[]{"prod"}))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 无激活 profile（开发/单测环境）+ 默认密钥 → 仅告警，不抛异常。 */
    @Test
    void noProfile_defaultSecret_doesNotThrow() {
        assertThatCode(() ->
                AuthSecretGuard.enforce(TokenService.DEFAULT_DEV_SECRET, new String[]{}))
                .doesNotThrowAnyException();
    }

    /** test profile + 默认密钥 → 不抛异常。 */
    @Test
    void testProfile_defaultSecret_doesNotThrow() {
        assertThatCode(() ->
                AuthSecretGuard.enforce(TokenService.DEFAULT_DEV_SECRET, new String[]{"test"}))
                .doesNotThrowAnyException();
    }

    /** prod + 自定义但不足 32 字节 → 拒绝。 */
    @Test
    void prod_shortCustomSecret_throws() {
        assertThatThrownBy(() ->
                AuthSecretGuard.enforce("my-super-secret-key-2026", new String[]{"prod"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    /** prod + 31 字节边界 → 拒绝。 */
    @Test
    void prod_31ByteSecret_throws() {
        assertThatThrownBy(() ->
                AuthSecretGuard.enforce(SECRET_31, new String[]{"prod"}))
                .isInstanceOf(IllegalStateException.class);
    }

    /** prod + 32 字节边界 → 通过。 */
    @Test
    void prod_32ByteSecret_doesNotThrow() {
        assertThatCode(() ->
                AuthSecretGuard.enforce(SECRET_32, new String[]{"prod"}))
                .doesNotThrowAnyException();
    }

    /** prod + 空密钥 → 拒绝。 */
    @Test
    void prod_emptySecret_throws() {
        assertThatThrownBy(() ->
                AuthSecretGuard.enforce("", new String[]{"prod"}))
                .isInstanceOf(IllegalStateException.class);
    }

    /** prod + null 密钥 → 拒绝。 */
    @Test
    void prod_nullSecret_throws() {
        assertThatThrownBy(() ->
                AuthSecretGuard.enforce(null, new String[]{"prod"}))
                .isInstanceOf(IllegalStateException.class);
    }

    /** test profile 允许短密钥。 */
    @Test
    void testProfile_shortSecret_doesNotThrow() {
        assertThatCode(() ->
                AuthSecretGuard.enforce(SECRET_31, new String[]{"test"}))
                .doesNotThrowAnyException();
    }
}
