package com.wenjin.config;

/**
 * 请求级当前用户上下文，基于 ThreadLocal 存储认证后的用户 ID。
 *
 * <p>由 {@link AuthContextInterceptor#preHandle} 写入，{@code afterCompletion} 中清除，
 * 确保每次请求结束后 ThreadLocal 被清理（防止线程池复用时数据污染）。</p>
 *
 * <p><strong>警告：</strong>ThreadLocal 不跨线程传播。勿在异步线程（如 ExecutorService 的 worker）
 * 里读取 {@code CurrentUser.get()}，应在请求线程中提前读取并传入。</p>
 */
public final class CurrentUser {

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    private CurrentUser() {}

    /** 设置当前请求的认证用户 ID。 */
    public static void set(Long userId) {
        HOLDER.set(userId);
    }

    /** 获取当前请求的认证用户 ID；未认证时返回 null。 */
    public static Long get() {
        return HOLDER.get();
    }

    /** 清除当前线程的用户 ID，防 ThreadLocal 泄漏。 */
    public static void clear() {
        HOLDER.remove();
    }
}
