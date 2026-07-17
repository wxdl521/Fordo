package com.wenjin.config;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.security.CurrentPrincipal;

/**
 * 请求归属断言工具：学生私有数据的 studentId 一律从 {@link CurrentUser} 派生。
 *
 * <p>兼容窗口内，客户端仍可附带遗留 {@code studentId} 字段/查询参数：若存在且不等于
 * principal 则 403；真正传给 service 的 ID 永远取 principal。</p>
 */
public final class AccessGuard {

    private AccessGuard() {}

    /**
     * 断言 studentId == 当前登录用户（兼容旧调用；null 或不等 → 403，未认证 → 401）。
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

    /**
     * 要求当前主体为学生，并解析有效学生 ID。
     * <ul>
     *   <li>未认证 → 401；非学生 → 403</li>
     *   <li>{@code optionalClientStudentId} 非空且 ≠ principal → 403</li>
     *   <li>返回值永远是 principal.userId()（忽略客户端声明的他人 ID）</li>
     * </ul>
     *
     * @param optionalClientStudentId 遗留请求体/查询参数中的 studentId，可空
     * @return 当前学生的 userId
     */
    public static long requireSelfStudent(Long optionalClientStudentId) {
        CurrentPrincipal principal = CurrentUser.requireStudent();
        long principalId = principal.userId();
        if (optionalClientStudentId != null && optionalClientStudentId != principalId) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问他人学情");
        }
        return principalId;
    }
}
