package com.wenjin.service;

import com.wenjin.common.BusinessException;
import com.wenjin.dto.TeacherCourseVO;
import com.wenjin.entity.Course;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.service.impl.TeacherCourseServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherCourseServiceImplTest {

    @Mock CourseMapper courseMapper;
    @Mock KgNodeMapper nodeMapper;
    @Mock KgEdgeMapper edgeMapper;
    @InjectMocks TeacherCourseServiceImpl service;

    @Test
    void list_includesDraftsAndMapsPublished() {
        Course published = new Course();
        published.setId(7L); published.setCode("AABBCCDDEE"); published.setName("已发布课"); published.setStatus(1);
        Course draft = new Course();
        draft.setId(8L); draft.setCode("FFEEDDCCBB"); draft.setName("草稿课"); draft.setStatus(0);
        when(courseMapper.selectList(any())).thenReturn(List.of(published, draft));

        List<TeacherCourseVO> out = service.list();

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getId()).isEqualTo(7L);
        assertThat(out.get(0).isPublished()).isTrue();
        assertThat(out.get(1).getId()).isEqualTo(8L);
        assertThat(out.get(1).isPublished()).isFalse();
    }

    @Test
    void create_generatesUniqueCodeAndInserts() {
        when(courseMapper.selectCount(any())).thenReturn(0L);
        when(courseMapper.insert(any(Course.class))).thenAnswer(inv -> {
            ((Course) inv.getArgument(0)).setId(42L);
            return 1;
        });

        TeacherCourseVO vo = service.create("软件工程", 9L);

        ArgumentCaptor<Course> cap = ArgumentCaptor.forClass(Course.class);
        verify(courseMapper).insert(cap.capture());
        Course saved = cap.getValue();
        assertThat(saved.getName()).isEqualTo("软件工程");
        assertThat(saved.getTeacherId()).isEqualTo(9L);
        assertThat(saved.getStatus()).isEqualTo(0);
        assertThat(saved.getCode()).matches("[0-9A-F]{10}");
        assertThat(vo.getId()).isEqualTo(42L);
        assertThat(vo.getName()).isEqualTo("软件工程");
        assertThat(vo.isPublished()).isFalse();
    }

    @Test
    void create_blankName_throws() {
        assertThatThrownBy(() -> service.create("  ", 9L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void create_nullTeacher_fallsBackToDemo() {
        ReflectionTestUtils.setField(service, "demoTeacherId", 1L);
        when(courseMapper.selectCount(any())).thenReturn(0L);
        when(courseMapper.insert(any(Course.class))).thenReturn(1);

        service.create("X", null);

        ArgumentCaptor<Course> cap = ArgumentCaptor.forClass(Course.class);
        verify(courseMapper).insert(cap.capture());
        assertThat(cap.getValue().getTeacherId()).isEqualTo(1L);
    }

    @Test
    void delete_removesEdgesThenNodesThenCourse() {
        Course c = new Course();
        c.setId(5L);
        when(courseMapper.selectById(5L)).thenReturn(c);

        service.delete(5L);

        InOrder order = inOrder(edgeMapper, nodeMapper, courseMapper);
        order.verify(edgeMapper).delete(any());
        order.verify(nodeMapper).delete(any());
        order.verify(courseMapper).deleteById(5L);
    }

    @Test
    void delete_missingCourse_throws() {
        when(courseMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.delete(404L))
                .isInstanceOf(BusinessException.class);
    }
}
