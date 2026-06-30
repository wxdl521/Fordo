package com.wenjin.config;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;

/**
 * 请求归属断言工具：要求 path/param 里的 studentId 必须等于当前认证用户。
 *
 * <p>不等或未认证则抛 {@link BusinessException}（与全局异常处理一致，确保响应 {@code code != 0}）。
 * 供学生侧端点在方法体第一行调用，在 {@code assertAccessibleByStudent} 之前。</p>
 */
public final class AccessGuard {

    private AccessGuard() {}

    /**
     * 断言 studentId == 当前登录用户。
     * <ul>
     *   <li>当前用户（{@link CurrentUser#get()}）为 null → 抛 UNAUTHORIZED（401）</li>
     *   <li>studentId 为 null 或与当前用户 id 不匹配 → 抛 FORBIDDEN（403）</li>
     * </ul>
     */
    public static void assertSelf(Long studentId) {
        Long currentUserId = CurrentUser.get();
        if (currentUserId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, ResultCode.UNAUTHORIZED.getMessage());
        }
        if (studentId == null || !studentId.equals(currentUserId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问他人学情");
        }
    }
}
