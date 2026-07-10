package com.wenjin.service;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.TeacherCourseVO;
import com.wenjin.entity.Course;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherCourseServiceImplTest {

    @Mock CourseMapper courseMapper;
    @Mock KgNodeMapper nodeMapper;
    @Mock KgEdgeMapper edgeMapper;
    @Mock CourseAccessService courseAccessService;

    private FeatureFlagsProperties featureFlags;
    private TeacherCourseServiceImpl service;

    @BeforeEach
    void setUp() {
        featureFlags = new FeatureFlagsProperties();
        featureFlags.setGraphReconcileEnabled(true);
        service = new TeacherCourseServiceImpl(courseMapper, nodeMapper, edgeMapper,
                courseAccessService, featureFlags);
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
