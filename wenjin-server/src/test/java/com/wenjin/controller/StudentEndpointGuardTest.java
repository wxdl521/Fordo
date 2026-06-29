package com.wenjin.controller;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.CourseWithMasteryVO;
import com.wenjin.dto.DiagnosticResultVO;
import com.wenjin.dto.GraphDataVO;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.dto.GraphImportResult;
import com.wenjin.dto.LearningPathVO;
import com.wenjin.dto.PaperVO;
import com.wenjin.dto.PathGenerateRequest;
import com.wenjin.dto.SubmitRequest;
import com.wenjin.dto.SubmitResult;
import com.wenjin.entity.Course;
import com.wenjin.service.CourseService;
import com.wenjin.service.DiagnosticResultService;
import com.wenjin.service.DiagnosticService;
import com.wenjin.service.GraphService;
import com.wenjin.service.GrowthService;
import com.wenjin.service.PathService;
import com.wenjin.vo.GrowthVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 学生侧数据端点的 deep-link 守卫接线验证（用手写 fake，不依赖 Mockito）：
 * 每个端点须在调用下游服务前先调 CourseService.assertAccessibleByStudent，
 * 守卫抛异常时短路（不触达下游服务）。
 */
class StudentEndpointGuardTest {

    /** 记录守卫入参；reject=true 时抛业务异常以验证短路。 */
    static class GuardSpy implements CourseService {
        Long seenStudentId;
        Long seenCourseId;
        boolean called;
        final boolean reject;

        GuardSpy() { this(false); }
        GuardSpy(boolean reject) { this.reject = reject; }

        public void enroll(Long studentId, Long courseId) {}
        public List<CourseWithMasteryVO> getMyCourses(Long studentId) { return List.of(); }
        public List<Course> getAvailableCourses() { return List.of(); }

        public void assertAccessibleByStudent(Long studentId, Long courseId) {
            called = true;
            seenStudentId = studentId;
            seenCourseId = courseId;
            if (reject) {
                throw new BusinessException(ResultCode.FORBIDDEN, "课程未发布");
            }
        }
    }

    static class GraphServiceFake implements GraphService {
        boolean called;
        public GraphImportResult importGraph(String courseCode, GraphImportRequest request) { return null; }
        public GraphDataVO getGraph(Long courseId) { return null; }
        public GraphDataVO getGraph(Long courseId, Long studentId) { called = true; return null; }
    }

    static class DiagnosticServiceFake implements DiagnosticService {
        boolean paperCalled;
        boolean submitCalled;
        public PaperVO composePaper(Long courseId) { paperCalled = true; return null; }
        public SubmitResult submit(SubmitRequest req) { submitCalled = true; return null; }
    }

    static class DiagnosticResultServiceFake implements DiagnosticResultService {
        boolean called;
        public DiagnosticResultVO getResult(Long studentId, Long courseId) { called = true; return null; }
    }

    static class PathServiceFake implements PathService {
        boolean generateCalled;
        boolean currentCalled;
        public LearningPathVO generate(PathGenerateRequest req) { generateCalled = true; return null; }
        public LearningPathVO getCurrent(Long studentId, Long courseId) { currentCalled = true; return null; }
        public void completeItem(Long itemId) {}
    }

    static class GrowthServiceFake implements GrowthService {
        boolean called;
        public GrowthVO getGrowth(Long studentId, Long courseId) { called = true; return null; }
    }

    // ---- GraphController /api/graph/{courseId} ----

    @Test
    void graph_invokesGuardWithStudentAndCourse() {
        GuardSpy guard = new GuardSpy();
        GraphServiceFake graph = new GraphServiceFake();
        new GraphController(graph, guard).getGraph(5L, 2L);

        assertThat(guard.seenStudentId).isEqualTo(2L);
        assertThat(guard.seenCourseId).isEqualTo(5L);
        assertThat(graph.called).isTrue();
    }

    @Test
    void graph_guardRejection_shortCircuits() {
        GuardSpy guard = new GuardSpy(true);
        GraphServiceFake graph = new GraphServiceFake();
        GraphController c = new GraphController(graph, guard);

        assertThatThrownBy(() -> c.getGraph(5L, 2L)).isInstanceOf(BusinessException.class);
        assertThat(graph.called).isFalse();
    }

    // ---- DiagnosticController ----

    @Test
    void diagnosticPaper_guardsWithNullStudent_publishGateOnly() {
        GuardSpy guard = new GuardSpy();
        DiagnosticServiceFake diag = new DiagnosticServiceFake();
        new DiagnosticController(diag, new DiagnosticResultServiceFake(), guard).paper(5L);

        assertThat(guard.called).isTrue();
        assertThat(guard.seenStudentId).isNull();
        assertThat(guard.seenCourseId).isEqualTo(5L);
        assertThat(diag.paperCalled).isTrue();
    }

    @Test
    void diagnosticResult_guardsWithStudentAndCourse_shortCircuitsOnReject() {
        GuardSpy guard = new GuardSpy(true);
        DiagnosticResultServiceFake result = new DiagnosticResultServiceFake();
        DiagnosticController c = new DiagnosticController(new DiagnosticServiceFake(), result, guard);

        assertThatThrownBy(() -> c.result(2L, 5L)).isInstanceOf(BusinessException.class);
        assertThat(result.called).isFalse();
    }

    @Test
    void diagnosticSubmit_guardsWithBodyStudentAndCourse() {
        GuardSpy guard = new GuardSpy();
        DiagnosticServiceFake diag = new DiagnosticServiceFake();
        SubmitRequest req = new SubmitRequest();
        req.setStudentId(2L);
        req.setCourseId(5L);
        new DiagnosticController(diag, new DiagnosticResultServiceFake(), guard).submit(req);

        assertThat(guard.seenStudentId).isEqualTo(2L);
        assertThat(guard.seenCourseId).isEqualTo(5L);
        assertThat(diag.submitCalled).isTrue();
    }

    // ---- PathController ----

    @Test
    void pathCurrent_guardsWithStudentAndCourse() {
        GuardSpy guard = new GuardSpy();
        PathServiceFake path = new PathServiceFake();
        new PathController(path, guard).current(2L, 5L);

        assertThat(guard.seenStudentId).isEqualTo(2L);
        assertThat(guard.seenCourseId).isEqualTo(5L);
        assertThat(path.currentCalled).isTrue();
    }

    @Test
    void pathGenerate_guardsWithBodyStudentAndCourse_shortCircuitsOnReject() {
        GuardSpy guard = new GuardSpy(true);
        PathServiceFake path = new PathServiceFake();
        PathController c = new PathController(path, guard);
        PathGenerateRequest req = new PathGenerateRequest();
        req.setStudentId(2L);
        req.setCourseId(5L);

        assertThatThrownBy(() -> c.generate(req)).isInstanceOf(BusinessException.class);
        assertThat(path.generateCalled).isFalse();
    }

    // ---- GrowthController ----

    @Test
    void growth_guardsWithStudentAndCourse() {
        GuardSpy guard = new GuardSpy();
        GrowthServiceFake growth = new GrowthServiceFake();
        new GrowthController(growth, guard).getGrowth(2L, 5L);

        assertThat(guard.seenStudentId).isEqualTo(2L);
        assertThat(guard.seenCourseId).isEqualTo(5L);
        assertThat(growth.called).isTrue();
    }
}
