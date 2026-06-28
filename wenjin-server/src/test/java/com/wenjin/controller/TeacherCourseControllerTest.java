package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.CreateCourseRequest;
import com.wenjin.dto.TeacherCourseVO;
import com.wenjin.service.TeacherCourseService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TeacherCourseControllerTest {

    @Test
    void create_passesNameAndHeaderTeacherId() {
        AtomicReference<String> seenName = new AtomicReference<>();
        AtomicReference<Long> seenTeacher = new AtomicReference<>();
        TeacherCourseService fake = new TeacherCourseService() {
            public List<TeacherCourseVO> list() { return List.of(); }
            public TeacherCourseVO create(String name, Long teacherId) {
                seenName.set(name); seenTeacher.set(teacherId);
                return new TeacherCourseVO(1L, "ABCDEF0123", name);
            }
            public void delete(Long courseId) {}
            public void setPublished(Long courseId, boolean published) {}
        };
        TeacherCourseController controller = new TeacherCourseController(fake);

        CreateCourseRequest req = new CreateCourseRequest();
        req.setName("新课");
        Result<TeacherCourseVO> res = controller.create(req, 9L);

        assertThat(seenName.get()).isEqualTo("新课");
        assertThat(seenTeacher.get()).isEqualTo(9L);
        assertThat(res.getData().getCode()).isEqualTo("ABCDEF0123");
    }

    @Test
    void delete_delegatesId() {
        AtomicReference<Long> deleted = new AtomicReference<>();
        TeacherCourseService fake = new TeacherCourseService() {
            public List<TeacherCourseVO> list() { return List.of(); }
            public TeacherCourseVO create(String name, Long teacherId) { return null; }
            public void delete(Long courseId) { deleted.set(courseId); }
            public void setPublished(Long courseId, boolean published) {}
        };
        TeacherCourseController controller = new TeacherCourseController(fake);

        controller.delete(3L);

        assertThat(deleted.get()).isEqualTo(3L);
    }

    @Test
    void setStatus_delegatesIdAndPublished() {
        AtomicReference<Long> seenId = new AtomicReference<>();
        AtomicReference<Boolean> seenPub = new AtomicReference<>();
        TeacherCourseService fake = new TeacherCourseService() {
            public List<TeacherCourseVO> list() { return List.of(); }
            public TeacherCourseVO create(String name, Long teacherId) { return null; }
            public void delete(Long courseId) {}
            public void setPublished(Long courseId, boolean published) {
                seenId.set(courseId); seenPub.set(published);
            }
        };
        TeacherCourseController controller = new TeacherCourseController(fake);

        com.wenjin.dto.UpdateCourseStatusRequest req = new com.wenjin.dto.UpdateCourseStatusRequest();
        req.setPublished(Boolean.TRUE);
        controller.setStatus(7L, req);

        assertThat(seenId.get()).isEqualTo(7L);
        assertThat(seenPub.get()).isTrue();
    }
}
