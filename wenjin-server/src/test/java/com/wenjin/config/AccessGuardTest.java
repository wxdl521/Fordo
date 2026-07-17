package com.wenjin.config;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AccessGuard 单测（无 Mockito，无 Spring 上下文）：
 * assertSelf 三态 + requireSelfStudent 兼容窗口语义。
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
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void mismatchedStudentId_throwsForbidden() {
        CurrentUser.set(1L); // 当前用户是 1，但 studentId=2
        assertThatThrownBy(() -> AccessGuard.assertSelf(2L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.FORBIDDEN.getCode()));
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
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    void requireSelfStudent_nullClientId_returnsPrincipal() {
        CurrentUser.set(2L);
        assertThat(AccessGuard.requireSelfStudent(null)).isEqualTo(2L);
    }

    @Test
    void requireSelfStudent_matchingClientId_returnsPrincipal() {
        CurrentUser.set(2L);
        assertThat(AccessGuard.requireSelfStudent(2L)).isEqualTo(2L);
    }

    @Test
    void requireSelfStudent_mismatchedClientId_throwsForbidden() {
        CurrentUser.set(2L);
        assertThatThrownBy(() -> AccessGuard.requireSelfStudent(9L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    void requireSelfStudent_anonymous_throwsUnauthorized() {
        assertThatThrownBy(() -> AccessGuard.requireSelfStudent(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void requireSelfStudent_teacher_throwsForbidden() {
        CurrentUser.set(new CurrentPrincipal(2L, UserRole.TEACHER));
        assertThatThrownBy(() -> AccessGuard.requireSelfStudent(2L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.FORBIDDEN.getCode()));
    }
}
