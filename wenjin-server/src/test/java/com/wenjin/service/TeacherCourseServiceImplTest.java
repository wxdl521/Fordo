package com.wenjin.service;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.TeacherCourseVO;
import com.wenjin.entity.Course;
import com.wenjin.mapper.CourseAccessMapper;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.impl.TeacherCourseServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherCourseServiceImplTest {

    @Mock CourseMapper courseMapper;
    @Mock KgNodeMapper nodeMapper;
    @Mock KgEdgeMapper edgeMapper;
    @Mock CourseAccessService courseAccessService;
    @Mock CourseAccessMapper courseAccessMapper;

    private FeatureFlagsProperties featureFlags;
    private TeacherCourseServiceImpl service;

    @BeforeEach
    void setUp() {
        featureFlags = new FeatureFlagsProperties();
        featureFlags.setGraphReconcileEnabled(true);
        lenient().when(courseAccessMapper.lockActiveCourse(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new TeacherCourseServiceImpl(courseMapper, nodeMapper, edgeMapper,
                courseAccessService, courseAccessMapper, featureFlags);
    }

    @AfterEach
    void clear() {
        CurrentUser.clear();
    }

    private static void asTeacher(long id) {
        CurrentUser.set(new CurrentPrincipal(id, UserRole.TEACHER));
    }

    private static void asAdmin(long id) {
        CurrentUser.set(new CurrentPrincipal(id, UserRole.ADMIN));
    }

    @Test
    void list_teacherOnlySeesOwnCourses() {
        asTeacher(11L);
        Course own = new Course();
        own.setId(7L); own.setCode("AABBCCDDEE"); own.setName("我的课"); own.setStatus(1); own.setTeacherId(11L);
        when(courseMapper.selectList(any())).thenReturn(List.of(own));

        List<TeacherCourseVO> out = service.list();

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getId()).isEqualTo(7L);
        assertThat(out.get(0).isPublished()).isTrue();
        verify(courseMapper).selectList(any());
    }

    @Test
    void list_adminSeesAll() {
        asAdmin(1L);
        Course a = new Course();
        a.setId(7L); a.setCode("A"); a.setName("课A"); a.setStatus(1);
        Course b = new Course();
        b.setId(8L); b.setCode("B"); b.setName("课B"); b.setStatus(0);
        when(courseMapper.selectList(any())).thenReturn(List.of(a, b));

        List<TeacherCourseVO> out = service.list();

        assertThat(out).hasSize(2);
        assertThat(out.get(1).isPublished()).isFalse();
    }

    @Test
    void create_usesCurrentPrincipalAsOwner() {
        asTeacher(9L);
        when(courseMapper.selectCount(any())).thenReturn(0L);
        when(courseMapper.insert(any(Course.class))).thenAnswer(inv -> {
            ((Course) inv.getArgument(0)).setId(42L);
            return 1;
        });

        TeacherCourseVO vo = service.create("软件工程");

        ArgumentCaptor<Course> cap = ArgumentCaptor.forClass(Course.class);
        verify(courseMapper).insert(cap.capture());
        Course saved = cap.getValue();
        assertThat(saved.getName()).isEqualTo("软件工程");
        assertThat(saved.getTeacherId()).isEqualTo(9L);
        assertThat(saved.getStatus()).isEqualTo(0);
        assertThat(saved.getCode()).matches("[0-9A-F]{10}");
        assertThat(vo.getId()).isEqualTo(42L);
        assertThat(vo.isPublished()).isFalse();
    }

    @Test
    void create_blankName_throws() {
        asTeacher(9L);
        assertThatThrownBy(() -> service.create("  "))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void delete_archivesCourseAndRelatedGraph_noHardDelete() {
        asTeacher(9L);
        doNothing().when(courseAccessService).assertManageCourse(5L);
        Course c = new Course();
        c.setId(5L);
        c.setStatus(1);
        when(courseMapper.selectById(5L)).thenReturn(c);
        when(courseMapper.updateById(any(Course.class))).thenReturn(1);
        when(nodeMapper.update(any(), any())).thenReturn(2);
        when(edgeMapper.update(any(), any())).thenReturn(3);

        service.delete(5L);

        ArgumentCaptor<Course> cap = ArgumentCaptor.forClass(Course.class);
        verify(courseMapper).updateById(cap.capture());
        assertThat(cap.getValue().getArchivedAt()).isNotNull();
        assertThat(cap.getValue().getStatus()).isZero();
        verify(courseAccessService, times(2)).assertManageCourse(5L);
        InOrder order = org.mockito.Mockito.inOrder(courseAccessService, courseAccessMapper, courseMapper);
        order.verify(courseAccessService).assertManageCourse(5L);
        order.verify(courseAccessMapper).lockActiveCourse(5L);
        order.verify(courseAccessService).assertManageCourse(5L);
        order.verify(courseMapper).selectById(5L);
        verify(nodeMapper).update(any(), any());
        verify(edgeMapper).update(any(), any());
        verify(courseMapper, never()).deleteById(any(Long.class));
        verify(nodeMapper, never()).delete(any());
        verify(edgeMapper, never()).delete(any());
    }

    @Test
    void delete_withRelatedData_stillArchives() {
        // 与旧行为相反：有节点/题库也允许归档，不硬删下游
        asTeacher(9L);
        doNothing().when(courseAccessService).assertManageCourse(5L);
        Course c = new Course();
        c.setId(5L);
        when(courseMapper.selectById(5L)).thenReturn(c);
        when(courseMapper.updateById(any(Course.class))).thenReturn(1);
        when(nodeMapper.update(any(), any())).thenReturn(1);
        when(edgeMapper.update(any(), any())).thenReturn(1);

        service.delete(5L);

        verify(courseMapper).updateById(any(Course.class));
        verify(courseMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void delete_featureDisabled_503() {
        asTeacher(9L);
        featureFlags.setGraphReconcileEnabled(false);
        assertThatThrownBy(() -> service.delete(5L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
        verify(courseMapper, never()).updateById(any(Course.class));
    }

    @Test
    void delete_crossTenant_404_beforeDelete() {
        asTeacher(11L);
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "资源不存在"))
                .when(courseAccessService).assertManageCourse(22L);

        assertThatThrownBy(() -> service.delete(22L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(404);
        verify(courseMapper, never()).deleteById(any(Long.class));
        verify(courseMapper, never()).updateById(any(Course.class));
        verify(courseAccessMapper, never()).lockActiveCourse(any());
    }

    @Test
    void restore_archivedCourseAndGraph_isExplicitAndUnpublished() {
        asTeacher(9L);
        when(courseAccessMapper.lockCourseIncludingArchived(5L)).thenReturn(5L);
        Course c = new Course();
        c.setId(5L);
        c.setCode("C1");
        c.setName("course-one");
        c.setStatus(1);
        c.setArchivedAt(java.time.LocalDateTime.now().minusDays(1));
        when(courseMapper.selectById(5L)).thenReturn(c);

        TeacherCourseVO vo = service.restore(5L);

        verify(courseAccessService, times(2)).assertManageCourseIncludingArchived(5L);
        InOrder order = org.mockito.Mockito.inOrder(
                courseAccessService, courseAccessMapper, courseMapper, nodeMapper, edgeMapper);
        order.verify(courseAccessService).assertManageCourseIncludingArchived(5L);
        order.verify(courseAccessMapper).lockCourseIncludingArchived(5L);
        order.verify(courseAccessService).assertManageCourseIncludingArchived(5L);
        order.verify(courseMapper).selectById(5L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Course>> courseUpdate =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(courseMapper).update(isNull(), courseUpdate.capture());
        assertThat(courseUpdate.getValue().getSqlSet()).contains("status", "archived_at");
        assertThat(courseUpdate.getValue().getParamNameValuePairs().values()).contains(0).containsNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.wenjin.entity.KgNode>> nodeUpdate =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(nodeMapper).update(isNull(), nodeUpdate.capture());
        assertThat(nodeUpdate.getValue().getSqlSet()).contains("archived_at");
        assertThat(nodeUpdate.getValue().getParamNameValuePairs().values()).containsNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.wenjin.entity.KgEdge>> edgeUpdate =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(edgeMapper).update(isNull(), edgeUpdate.capture());
        assertThat(edgeUpdate.getValue().getSqlSet()).contains("archived_at");
        assertThat(edgeUpdate.getValue().getParamNameValuePairs().values()).containsNull();

        assertThat(vo.getId()).isEqualTo(5L);
        assertThat(vo.getCode()).isEqualTo("C1");
        assertThat(vo.isPublished()).isFalse();
    }

    @Test
    void restore_activeCourse_isIdempotentButAlwaysUnpublished() {
        asTeacher(9L);
        when(courseAccessMapper.lockCourseIncludingArchived(5L)).thenReturn(5L);
        Course c = new Course();
        c.setId(5L);
        c.setCode("C1");
        c.setName("course-one");
        c.setStatus(1);
        when(courseMapper.selectById(5L)).thenReturn(c);

        TeacherCourseVO vo = service.restore(5L);

        assertThat(vo.isPublished()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Course>> update =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(courseMapper).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSet()).contains("status", "archived_at");
        assertThat(update.getValue().getParamNameValuePairs().values()).contains(0).containsNull();
        verify(nodeMapper, never()).update(any(), any());
        verify(edgeMapper, never()).update(any(), any());
    }

    @Test
    void restore_missingLockedRow_404BeforeMutation() {
        asTeacher(9L);
        when(courseAccessMapper.lockCourseIncludingArchived(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.restore(404L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.NOT_FOUND.getCode());

        verify(courseAccessService).assertManageCourseIncludingArchived(404L);
        verify(courseMapper, never()).selectById(any());
        verify(courseMapper, never()).update(any(), any());
    }

    @Test
    void setPublished_true_setsStatus1() {
        asTeacher(9L);
        doNothing().when(courseAccessService).assertManageCourse(5L);
        Course c = new Course();
        c.setId(5L); c.setStatus(0);
        when(courseMapper.selectById(5L)).thenReturn(c);

        service.setPublished(5L, true);

        ArgumentCaptor<Course> cap = ArgumentCaptor.forClass(Course.class);
        verify(courseMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(1);
    }

    @Test
    void setPublished_false_setsStatus0() {
        asTeacher(9L);
        doNothing().when(courseAccessService).assertManageCourse(5L);
        Course c = new Course();
        c.setId(5L); c.setStatus(1);
        when(courseMapper.selectById(5L)).thenReturn(c);

        service.setPublished(5L, false);

        ArgumentCaptor<Course> cap = ArgumentCaptor.forClass(Course.class);
        verify(courseMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(0);
    }

    @Test
    void setPublished_missingCourse_throws() {
        asTeacher(9L);
        doNothing().when(courseAccessService).assertManageCourse(404L);
        when(courseMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.setPublished(404L, true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void setPublished_otherTeacher_404() {
        asTeacher(11L);
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "资源不存在"))
                .when(courseAccessService).assertManageCourse(eq(22L));

        assertThatThrownBy(() -> service.setPublished(22L, true))
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(404);
        verify(courseMapper, never()).updateById(any(Course.class));
    }
}
