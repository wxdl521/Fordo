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
    void create_passesNameOnly_serviceOwnsPrincipal() {
        AtomicReference<String> seenName = new AtomicReference<>();
        TeacherCourseService fake = new TeacherCourseService() {
            public List<TeacherCourseVO> list() { return List.of(); }
            public TeacherCourseVO create(String name) {
                seenName.set(name);
                return new TeacherCourseVO(1L, "ABCDEF0123", name);
            }
            public void delete(Long courseId) {}
            public TeacherCourseVO restore(Long courseId) { return null; }
            public void setPublished(Long courseId, boolean published) {}
        };
        TeacherCourseController controller = new TeacherCourseController(fake);

        CreateCourseRequest req = new CreateCourseRequest();
        req.setName("新课");
        Result<TeacherCourseVO> res = controller.create(req);

        assertThat(seenName.get()).isEqualTo("新课");
        assertThat(res.getData().getCode()).isEqualTo("ABCDEF0123");
    }

    @Test
    void delete_delegatesId() {
        AtomicReference<Long> deleted = new AtomicReference<>();
        TeacherCourseService fake = new TeacherCourseService() {
            public List<TeacherCourseVO> list() { return List.of(); }
            public TeacherCourseVO create(String name) { return null; }
            public void delete(Long courseId) { deleted.set(courseId); }
            public TeacherCourseVO restore(Long courseId) { return null; }
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
            public TeacherCourseVO create(String name) { return null; }
            public void delete(Long courseId) {}
            public TeacherCourseVO restore(Long courseId) { return null; }
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

    @Test
    void restore_delegatesIdAndReturnsUnpublishedCourse() {
        AtomicReference<Long> restored = new AtomicReference<>();
        TeacherCourseVO expected = new TeacherCourseVO(3L, "C1", "course-one", false);
        TeacherCourseService fake = new TeacherCourseService() {
            public List<TeacherCourseVO> list() { return List.of(); }
            public TeacherCourseVO create(String name) { return null; }
            public void delete(Long courseId) {}
            public TeacherCourseVO restore(Long courseId) {
                restored.set(courseId);
                return expected;
            }
            public void setPublished(Long courseId, boolean published) {}
        };
        TeacherCourseController controller = new TeacherCourseController(fake);

        Result<TeacherCourseVO> result = controller.restore(3L);

        assertThat(restored.get()).isEqualTo(3L);
        assertThat(result.getData()).isSameAs(expected);
        assertThat(result.getData().isPublished()).isFalse();
    }
}
