package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.dto.CourseWithMasteryVO;
import com.wenjin.entity.Course;
import com.wenjin.entity.StudentCourse;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.StudentCourseMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.impl.CourseServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CourseServiceImplTest {

    @Mock
    private StudentCourseMapper studentCourseMapper;
    @Mock
    private CourseMapper courseMapper;
    @Mock
    private StudentMasteryMapper studentMasteryMapper;

    @InjectMocks
    private CourseServiceImpl service;

    // ---- enroll ----

    @Test
    void enroll_success() {
        Course course = new Course();
        course.setId(1L);
        course.setStatus(1);
        when(courseMapper.selectById(1L)).thenReturn(course);
        when(studentCourseMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(studentCourseMapper.insert(any(StudentCourse.class))).thenReturn(1);

        service.enroll(2L, 1L);

        verify(studentCourseMapper).insert(any(StudentCourse.class));
    }

    @Test
    void enroll_courseNotFound_throws() {
        when(courseMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.enroll(2L, 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("课程不存在");
    }

    @Test
    void enroll_courseDisabled_throws() {
        Course course = new Course();
        course.setId(1L);
        course.setStatus(0);
        when(courseMapper.selectById(1L)).thenReturn(course);

        assertThatThrownBy(() -> service.enroll(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("课程已停用");
    }

    @Test
    void enroll_alreadyEnrolled_idempotent() {
        Course course = new Course();
        course.setId(1L);
        course.setStatus(1);
        when(courseMapper.selectById(1L)).thenReturn(course);
        when(studentCourseMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        service.enroll(2L, 1L);

        // should NOT insert again
        verify(studentCourseMapper, never()).insert(any(StudentCourse.class));
    }

    // ---- getMyCourses ----

    @Test
    void getMyCourses_returnsEnrolledCoursesWithMastery() {
        // student 2 enrolled in course 1
        StudentCourse sc = new StudentCourse();
        sc.setStudentId(2L);
        sc.setCourseId(1L);
        when(studentCourseMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(sc));

        Course course = new Course();
        course.setId(1L);
        course.setCode("SE01");
        course.setName("软件工程");
        course.setDescription("desc");
        when(courseMapper.selectBatchIds(anyList()))
                .thenReturn(Collections.singletonList(course));

        // mastery: 1 mastered (level=2), 1 weak (level=1)
        StudentMastery m1 = new StudentMastery();
        m1.setStudentId(2L);
        m1.setCourseId(1L);
        m1.setMasteryLevel(2);
        StudentMastery m2 = new StudentMastery();
        m2.setStudentId(2L);
        m2.setCourseId(1L);
        m2.setMasteryLevel(1);
        when(studentMasteryMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(m1, m2));

        List<CourseWithMasteryVO> result = service.getMyCourses(2L);

        assertThat(result).hasSize(1);
        CourseWithMasteryVO vo = result.get(0);
        assertThat(vo.getCourseId()).isEqualTo(1L);
        assertThat(vo.getCode()).isEqualTo("SE01");
        assertThat(vo.getMasteredCount()).isEqualTo(1L);
        assertThat(vo.getWeakCount()).isEqualTo(1L);
        assertThat(vo.getUnlearnedCount()).isEqualTo(0L);
    }

    @Test
    void getMyCourses_noEnrollments_returnsEmpty() {
        when(studentCourseMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        List<CourseWithMasteryVO> result = service.getMyCourses(2L);

        assertThat(result).isEmpty();
    }

    @Test
    void getMyCourses_noMastery_allUnlearned() {
        StudentCourse sc = new StudentCourse();
        sc.setStudentId(2L);
        sc.setCourseId(1L);
        when(studentCourseMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(sc));

        Course course = new Course();
        course.setId(1L);
        course.setCode("SE01");
        course.setName("软件工程");
        when(courseMapper.selectBatchIds(anyList()))
                .thenReturn(Collections.singletonList(course));

        when(studentMasteryMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        List<CourseWithMasteryVO> result = service.getMyCourses(2L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMasteredCount()).isEqualTo(0L);
        assertThat(result.get(0).getWeakCount()).isEqualTo(0L);
        assertThat(result.get(0).getUnlearnedCount()).isEqualTo(0L);
    }

    // ---- getAvailableCourses ----

    @Test
    void getAvailableCourses_returnsEnabledCourses() {
        Course c1 = new Course();
        c1.setId(1L);
        c1.setStatus(1);
        Course c2 = new Course();
        c2.setId(2L);
        c2.setStatus(1);
        when(courseMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(c1, c2));

        List<Course> result = service.getAvailableCourses();

        assertThat(result).hasSize(2);
    }

    // ---- autoEnrollAll ----

    @Test
    void autoEnrollAll_enrollsAllAvailableCourses() {
        Course c1 = new Course();
        c1.setId(1L);
        Course c2 = new Course();
        c2.setId(2L);
        when(courseMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(c1, c2));

        // no existing enrollments
        when(studentCourseMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());
        when(studentCourseMapper.insert(any(StudentCourse.class))).thenReturn(1);

        service.autoEnrollAll(2L);

        verify(studentCourseMapper, times(2)).insert(any(StudentCourse.class));
    }

    @Test
    void autoEnrollAll_skipsAlreadyEnrolled() {
        Course c1 = new Course();
        c1.setId(1L);
        Course c2 = new Course();
        c2.setId(2L);
        when(courseMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(c1, c2));

        // already enrolled in course 1
        StudentCourse existing = new StudentCourse();
        existing.setStudentId(2L);
        existing.setCourseId(1L);
        when(studentCourseMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(existing));
        when(studentCourseMapper.insert(any(StudentCourse.class))).thenReturn(1);

        service.autoEnrollAll(2L);

        // only 1 insert (course 2)
        verify(studentCourseMapper, times(1)).insert(any(StudentCourse.class));
    }

    @Test
    void autoEnrollAll_noAvailableCourses_doesNothing() {
        when(courseMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        service.autoEnrollAll(2L);

        verify(studentCourseMapper, never()).insert(any(StudentCourse.class));
    }
}
