package com.wenjin.security;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.mapper.CourseAccessMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourseAccessServiceTest {

    @Mock CourseAccessMapper accessMapper;
    @InjectMocks CourseAccessService service;

    @AfterEach
    void clear() {
        CurrentUser.clear();
    }

    private static CurrentPrincipal principal(long id, UserRole role) {
        return new CurrentPrincipal(id, role);
    }

    // ───────── assertManageCourse ─────────

    @Test
    @DisplayName("教师可管理自己的课程")
    void teacherCanManageOwnCourse() {
        CurrentUser.set(principal(11L, UserRole.TEACHER));
        when(accessMapper.isOwnedCourse(22L, 11L)).thenReturn(true);

        assertThatCode(() -> service.assertManageCourse(22L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("教师管理他人课程 → 404（非 403）")
    void teacherCannotManageOthersCourse() {
        CurrentUser.set(principal(11L, UserRole.TEACHER));
        when(accessMapper.isOwnedCourse(22L, 11L)).thenReturn(false);

        assertThatThrownBy(() -> service.assertManageCourse(22L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("教师管理不存在课程 → 404")
    void teacherMissingCourse_404() {
        CurrentUser.set(principal(11L, UserRole.TEACHER));
        when(accessMapper.isOwnedCourse(404L, 11L)).thenReturn(false);

        assertThatThrownBy(() -> service.assertManageCourse(404L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(404);
    }

    @Test
    @DisplayName("遗留无 owner 课程：教师 404，admin 可管理")
    void legacyNoOwner_onlyAdmin() {
        CurrentUser.set(principal(11L, UserRole.TEACHER));
        when(accessMapper.isOwnedCourse(5L, 11L)).thenReturn(false);
        assertThatThrownBy(() -> service.assertManageCourse(5L))
                .extracting("code").isEqualTo(404);
        verify(accessMapper).isOwnedCourse(5L, 11L);

        org.mockito.Mockito.clearInvocations(accessMapper);
        CurrentUser.set(principal(1L, UserRole.ADMIN));
        when(accessMapper.courseExists(5L)).thenReturn(true);
        assertThatCode(() -> service.assertManageCourse(5L)).doesNotThrowAnyException();
        verify(accessMapper, never()).isOwnedCourse(any(), any());
        verify(accessMapper).courseExists(5L);
    }

    @Test
    @DisplayName("ADMIN 可管理任意存在课程")
    void adminBypassesOwnership() {
        CurrentUser.set(principal(1L, UserRole.ADMIN));
        when(accessMapper.courseExists(99L)).thenReturn(true);

        assertThatCode(() -> service.assertManageCourse(99L)).doesNotThrowAnyException();
        verify(accessMapper, never()).isOwnedCourse(any(), any());
    }

    @Test
    @DisplayName("ADMIN 对不存在课程仍 404")
    void adminMissingCourse_404() {
        CurrentUser.set(principal(1L, UserRole.ADMIN));
        when(accessMapper.courseExists(404L)).thenReturn(false);

        assertThatThrownBy(() -> service.assertManageCourse(404L))
                .extracting("code").isEqualTo(404);
    }

    @Test
    @DisplayName("未认证 → 401")
    void unauthenticated_401() {
        CurrentUser.clear();
        assertThatThrownBy(() -> service.assertManageCourse(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("学生角色 → 403")
    void student_403() {
        CurrentUser.set(principal(2L, UserRole.STUDENT));
        assertThatThrownBy(() -> service.assertManageCourse(1L))
                .extracting("code").isEqualTo(ResultCode.FORBIDDEN.getCode());
    }

    // ───────── assertManageCourseCode ─────────

    @Test
    @DisplayName("按 code 管理自己的课返回 courseId")
    void manageByCode_own() {
        CurrentUser.set(principal(11L, UserRole.TEACHER));
        when(accessMapper.courseIdByCode("C1")).thenReturn(22L);
        when(accessMapper.isOwnedCourse(22L, 11L)).thenReturn(true);

        assertThat(service.assertManageCourseCode("C1")).isEqualTo(22L);
    }

    @Test
    @DisplayName("按 code 管理他人课 → 404")
    void manageByCode_otherTeacher_404() {
        CurrentUser.set(principal(11L, UserRole.TEACHER));
        when(accessMapper.courseIdByCode("C2")).thenReturn(33L);
        when(accessMapper.isOwnedCourse(33L, 11L)).thenReturn(false);

        assertThatThrownBy(() -> service.assertManageCourseCode("C2"))
                .extracting("code").isEqualTo(404);
    }

    @Test
    @DisplayName("不存在的 code → 404")
    void manageByCode_missing_404() {
        CurrentUser.set(principal(11L, UserRole.TEACHER));
        when(accessMapper.courseIdByCode("NOPE")).thenReturn(null);

        assertThatThrownBy(() -> service.assertManageCourseCode("NOPE"))
                .extracting("code").isEqualTo(404);
    }

    // ───────── assertManageEdge / Node / Question ─────────

    @Test
    @DisplayName("教师不能采纳他人课程的边（示例用例）")
    void teacherCannotManageEdgeFromAnotherTeachersCourse() {
        CurrentUser.set(principal(11L, UserRole.TEACHER));
        when(accessMapper.courseIdForEdge(900L)).thenReturn(22L);
        when(accessMapper.isOwnedCourse(22L, 11L)).thenReturn(false);

        assertThatThrownBy(() -> service.assertManageEdge(900L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(404);
    }

    @Test
    @DisplayName("边不存在 → 404")
    void edgeMissing_404() {
        CurrentUser.set(principal(11L, UserRole.TEACHER));
        when(accessMapper.courseIdForEdge(900L)).thenReturn(null);

        assertThatThrownBy(() -> service.assertManageEdge(900L))
                .extracting("code").isEqualTo(404);
    }

    @Test
    @DisplayName("节点归属校验通过返回 courseId")
    void manageNode_ok() {
        CurrentUser.set(principal(11L, UserRole.TEACHER));
        when(accessMapper.courseIdForNode(7L)).thenReturn(22L);
        when(accessMapper.isOwnedCourse(22L, 11L)).thenReturn(true);

        assertThat(service.assertManageNode(7L)).isEqualTo(22L);
    }

    @Test
    @DisplayName("题目归属：他人课程 → 404")
    void manageQuestion_other_404() {
        CurrentUser.set(principal(11L, UserRole.TEACHER));
        when(accessMapper.courseIdForQuestion(50L)).thenReturn(99L);
        when(accessMapper.isOwnedCourse(99L, 11L)).thenReturn(false);

        assertThatThrownBy(() -> service.assertManageQuestion(50L))
                .extracting("code").isEqualTo(404);
    }

    @Test
    @DisplayName("ADMIN 管理边不校验 isOwnedCourse")
    void adminManageEdge_bypass() {
        CurrentUser.set(principal(1L, UserRole.ADMIN));
        when(accessMapper.courseIdForEdge(900L)).thenReturn(22L);

        assertThat(service.assertManageEdge(900L)).isEqualTo(22L);
        verify(accessMapper, never()).isOwnedCourse(any(), any());
    }

    // ───────── 批量题目 ─────────

    @Test
    @DisplayName("批量题目全部属于目标课 → 通过")
    void questionsBelongToCourse_ok() {
        when(accessMapper.countQuestionsInCourse(eq(22L), any())).thenReturn(2L);

        assertThatCode(() -> service.assertQuestionsBelongToCourse(22L, List.of(1L, 2L)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("批量题目含跨课或缺失 → 404")
    void questionsBelongToCourse_crossTenant_404() {
        when(accessMapper.countQuestionsInCourse(eq(22L), any())).thenReturn(1L);

        assertThatThrownBy(() -> service.assertQuestionsBelongToCourse(22L, List.of(1L, 2L)))
                .extracting("code").isEqualTo(404);
    }
}
