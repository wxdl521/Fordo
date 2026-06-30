package com.wenjin.config;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AccessGuard.assertSelf 的三态单测（无 Mockito，无 Spring 上下文）：
 * 1. 当前用户为 null → UNAUTHORIZED
 * 2. studentId 与当前用户不匹配 → FORBIDDEN
 * 3. 完全匹配 → 放行不抛
 */
class AccessGuardTest {

    @AfterEach
    void clearCurrentUser() {
        CurrentUser.clear();
    }

    @Test
    void nullCurrentUser_throwsUnauthorized() {
        // 未设置 CurrentUser（匿名请求）
        assertThatThrownBy(() -> AccessGuard.assertSelf(2L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    int code = ((BusinessException) ex).getCode();
                    assert code == ResultCode.UNAUTHORIZED.getCode()
                            : "期望 401，实际 " + code;
                });
    }

    @Test
    void mismatchedStudentId_throwsForbidden() {
        CurrentUser.set(1L); // 当前用户是 1，但 studentId=2
        assertThatThrownBy(() -> AccessGuard.assertSelf(2L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    int code = ((BusinessException) ex).getCode();
                    assert code == ResultCode.FORBIDDEN.getCode()
                            : "期望 403，实际 " + code;
                });
    }

    @Test
    void matchingStudentId_noException() {
        CurrentUser.set(2L);
        // 相同 id，断言应放行
        assertThatCode(() -> AccessGuard.assertSelf(2L)).doesNotThrowAnyException();
    }

    @Test
    void nullStudentId_throwsForbidden() {
        // 端点 studentId 为 null（不应通过）→ FORBIDDEN
        CurrentUser.set(2L);
        assertThatThrownBy(() -> AccessGuard.assertSelf(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    int code = ((BusinessException) ex).getCode();
                    assert code == ResultCode.FORBIDDEN.getCode()
                            : "期望 403，实际 " + code;
                });
    }
}
