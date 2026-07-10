package com.wenjin.config;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;

/**
 * 请求级当前用户上下文，基于 ThreadLocal 存储认证后的可信 {@link CurrentPrincipal}。
 *
 * <p>由 {@link AuthContextInterceptor#preHandle} 写入，{@code afterCompletion} 中清除，
 * 确保每次请求结束后 ThreadLocal 被清理（防止线程池复用时数据污染）。</p>
 *
 * <p><strong>警告：</strong>ThreadLocal 不跨线程传播。勿在异步线程（如 ExecutorService 的 worker）
 * 里读取 {@code CurrentUser.get()}，应在请求线程中提前读取并传入。</p>
 */
public final class CurrentUser {

    private static final ThreadLocal<CurrentPrincipal> HOLDER = new ThreadLocal<>();

    private CurrentUser() {}

    /** 设置当前请求的可信主体。 */
    public static void set(CurrentPrincipal principal) {
        if (principal == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(principal);
        }
    }

    /**
     * 兼容写入：仅用户 ID（角色缺省为 {@link UserRole#STUDENT}）。
     * <p>供既有测试与异步透传使用；生产鉴权路径应写入完整 {@link CurrentPrincipal}。</p>
     */
    public static void set(Long userId) {
        if (userId == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(new CurrentPrincipal(userId, UserRole.STUDENT));
        }
    }

    /** 获取当前请求的认证用户 ID；未认证时返回 null。 */
    public static Long get() {
        CurrentPrincipal p = HOLDER.get();
        return p == null ? null : p.userId();
    }

    /** 获取当前可信主体；未认证时返回 null。 */
    public static CurrentPrincipal getPrincipal() {
        return HOLDER.get();
    }

    /** 要求已认证；否则 401。 */
    public static CurrentPrincipal require() {
        CurrentPrincipal p = HOLDER.get();
        if (p == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, ResultCode.UNAUTHORIZED.getMessage());
        }
        return p;
    }

    /** 要求学生角色；否则 401/403。 */
    public static CurrentPrincipal requireStudent() {
        CurrentPrincipal p = require();
        if (!p.isStudent()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无访问权限：仅学生可访问该功能");
        }
        return p;
    }

    /** 要求教师或管理员（教师端路由）；否则 401/403。 */
    public static CurrentPrincipal requireTeacher() {
        CurrentPrincipal p = require();
        if (!p.isTeacher() && !p.isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无访问权限：仅教师可访问该功能");
        }
        return p;
    }

    /** 要求系统管理员；否则 401/403。 */
    public static CurrentPrincipal requireAdmin() {
        CurrentPrincipal p = require();
        if (!p.isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无访问权限：仅系统管理员可访问该功能");
        }
        return p;
    }

    /** 清除当前线程的主体，防 ThreadLocal 泄漏。 */
    public static void clear() {
        HOLDER.remove();
    }
}
