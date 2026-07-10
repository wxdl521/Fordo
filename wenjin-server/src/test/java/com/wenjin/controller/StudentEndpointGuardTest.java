package com.wenjin.controller;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.CourseWithMasteryVO;
import com.wenjin.dto.DiagnosticResultVO;
import com.wenjin.dto.EnrollRequest;
import com.wenjin.dto.GraphDataVO;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.dto.GraphImportResult;
import com.wenjin.dto.LearningPathVO;
import com.wenjin.dto.PaperVO;
import com.wenjin.dto.PathGenerateRequest;
import com.wenjin.dto.SubmitRequest;
import com.wenjin.dto.SubmitResult;
import com.wenjin.entity.Course;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.CourseService;
import com.wenjin.service.DiagnosticResultService;
import com.wenjin.service.DiagnosticService;
import com.wenjin.service.GraphService;
import com.wenjin.service.GrowthService;
import com.wenjin.service.PathService;
import com.wenjin.vo.GrowthVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 学生侧数据端点的 deep-link 守卫接线验证（用手写 fake，不依赖 Mockito）：
 * 每个端点须从 principal 派生学生 ID，并在调用下游服务前调 CourseService.assertAccessibleByStudent，
 * 守卫抛异常时短路（不触达下游服务）。
 */
class StudentEndpointGuardTest {

    /** 记录守卫入参；reject=true 时抛业务异常以验证短路。 */
    static class GuardSpy implements CourseService {
        Long seenStudentId;
        Long seenCourseId;
        boolean called;
        final boolean reject;
        final AtomicLong enrolledStudentId = new AtomicLong(-1);
        final AtomicLong enrolledCourseId = new AtomicLong(-1);

        GuardSpy() { this(false); }
        GuardSpy(boolean reject) { this.reject = reject; }

        public void enroll(Long studentId, Long courseId) {
            enrolledStudentId.set(studentId);
            enrolledCourseId.set(courseId);
        }
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
        Long seenStudentId;
        public GraphImportResult importGraph(String courseCode, GraphImportRequest request) { return null; }
        public GraphDataVO getGraph(Long courseId) { return null; }
        public GraphDataVO getGraph(Long courseId, Long studentId) {
            called = true;
            seenStudentId = studentId;
            return null;
        }
    }

    static class DiagnosticServiceFake implements DiagnosticService {
        boolean paperCalled;
        boolean submitCalled;
        Long submitStudentId;
        public PaperVO composePaper(Long courseId) { paperCalled = true; return null; }
        public SubmitResult submit(SubmitRequest req) {
            submitCalled = true;
            submitStudentId = req.getStudentId();
            return null;
        }
    }

    static class DiagnosticResultServiceFake implements DiagnosticResultService {
        boolean called;
        Long seenStudentId;
        public DiagnosticResultVO getResult(Long studentId, Long courseId) {
            called = true;
            seenStudentId = studentId;
            return null;
        }
    }

    static class PathServiceFake implements PathService {
        boolean generateCalled;
        boolean currentCalled;
        Long generateStudentId;
        public LearningPathVO generate(PathGenerateRequest req) {
            generateCalled = true;
            generateStudentId = req.getStudentId();
            return null;
        }
        public LearningPathVO getCurrent(Long studentId, Long courseId) { currentCalled = true; return null; }
        public void completeItem(Long itemId) {}
    }

    static class GrowthServiceFake implements GrowthService {
        boolean called;
        public GrowthVO getGrowth(Long studentId, Long courseId) { called = true; return null; }
    }

    /** 每次测试后清除 CurrentUser，防止 ThreadLocal 泄漏影响其它用例。 */
    @AfterEach
    void clearCurrentUser() {
        CurrentUser.clear();
    }

    // ---- CourseController /api/course/enroll ----

    @Test
    void enroll_usesPrincipalStudentId() {
        CurrentUser.set(2L);
        GuardSpy guard = new GuardSpy();
        EnrollRequest req = new EnrollRequest();
        req.setCourseId(5L);
        new CourseController(guard).enroll(req);

        assertThat(guard.enrolledStudentId.get()).isEqualTo(2L);
        assertThat(guard.enrolledCourseId.get()).isEqualTo(5L);
    }

    @Test
    void enroll_teacherRole_throwsForbidden() {
        CurrentUser.set(new CurrentPrincipal(2L, UserRole.TEACHER));
        GuardSpy guard = new GuardSpy();
        EnrollRequest req = new EnrollRequest();
        req.setCourseId(5L);

        assertThatThrownBy(() -> new CourseController(guard).enroll(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.FORBIDDEN.getCode()));
        assertThat(guard.enrolledStudentId.get()).isEqualTo(-1L);
    }

    @Test
    void enroll_anonymous_throwsUnauthorized() {
        GuardSpy guard = new GuardSpy();
        EnrollRequest req = new EnrollRequest();
        req.setCourseId(5L);

        assertThatThrownBy(() -> new CourseController(guard).enroll(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.UNAUTHORIZED.getCode()));
    }

    // ---- GraphController /api/graph/{courseId} ----

    @Test
    void graph_anonymous_publicGraph_noMastery() {
        GuardSpy guard = new GuardSpy();
        GraphServiceFake graph = new GraphServiceFake();
        new GraphController(graph, guard).getGraph(5L);

        assertThat(guard.seenStudentId).isNull();
        assertThat(guard.seenCourseId).isEqualTo(5L);
        assertThat(graph.called).isTrue();
        assertThat(graph.seenStudentId).isNull();
    }

    @Test
    void graph_loggedInStudent_attachesPrincipalMastery() {
        CurrentUser.set(2L);
        GuardSpy guard = new GuardSpy();
        GraphServiceFake graph = new GraphServiceFake();
        new GraphController(graph, guard).getGraph(5L);

        assertThat(guard.seenStudentId).isEqualTo(2L);
        assertThat(guard.seenCourseId).isEqualTo(5L);
        assertThat(graph.called).isTrue();
        assertThat(graph.seenStudentId).isEqualTo(2L);
    }

    @Test
    void graph_teacherLoggedIn_publicGraph_noMastery() {
        CurrentUser.set(new CurrentPrincipal(2L, UserRole.TEACHER));
        GuardSpy guard = new GuardSpy();
        GraphServiceFake graph = new GraphServiceFake();
        new GraphController(graph, guard).getGraph(5L);

        assertThat(guard.seenStudentId).isNull();
        assertThat(graph.seenStudentId).isNull();
        assertThat(graph.called).isTrue();
    }

    @Test
    void graph_guardRejection_shortCircuits() {
        CurrentUser.set(2L);
        GuardSpy guard = new GuardSpy(true);
        GraphServiceFake graph = new GraphServiceFake();
        GraphController c = new GraphController(graph, guard);

        assertThatThrownBy(() -> c.getGraph(5L)).isInstanceOf(BusinessException.class);
        assertThat(graph.called).isFalse();
    }

    // ---- DiagnosticController ----

    @Test
    void diagnosticPaper_guardsWithNullStudent_publishGateOnly() {
        CurrentUser.set(2L);
        GuardSpy guard = new GuardSpy();
        DiagnosticServiceFake diag = new DiagnosticServiceFake();
        new DiagnosticController(diag, new DiagnosticResultServiceFake(), guard).paper(5L);

        assertThat(guard.called).isTrue();
        assertThat(guard.seenStudentId).isNull();
        assertThat(guard.seenCourseId).isEqualTo(5L);
        assertThat(diag.paperCalled).isTrue();
    }

    @Test
    void diagnosticPaper_teacherRole_throwsForbidden() {
        CurrentUser.set(new CurrentPrincipal(2L, UserRole.TEACHER));
        DiagnosticController c =
                new DiagnosticController(new DiagnosticServiceFake(), new DiagnosticResultServiceFake(), new GuardSpy());

        assertThatThrownBy(() -> c.paper(5L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    void diagnosticResult_guardsWithPrincipal_shortCircuitsOnReject() {
        CurrentUser.set(2L);
        GuardSpy guard = new GuardSpy(true);
        DiagnosticResultServiceFake result = new DiagnosticResultServiceFake();
        DiagnosticController c = new DiagnosticController(new DiagnosticServiceFake(), result, guard);

        assertThatThrownBy(() -> c.result(2L, 5L)).isInstanceOf(BusinessException.class);
        assertThat(result.called).isFalse();
    }

    @Test
    void diagnosticResult_omittedClientStudentId_usesPrincipal() {
        CurrentUser.set(2L);
        GuardSpy guard = new GuardSpy();
        DiagnosticResultServiceFake result = new DiagnosticResultServiceFake();
        new DiagnosticController(new DiagnosticServiceFake(), result, guard).result(null, 5L);

        assertThat(guard.seenStudentId).isEqualTo(2L);
        assertThat(result.seenStudentId).isEqualTo(2L);
        assertThat(result.called).isTrue();
    }

    @Test
    void diagnosticSubmit_guardsWithBodyStudentAndCourse() {
        CurrentUser.set(2L);
        GuardSpy guard = new GuardSpy();
        DiagnosticServiceFake diag = new DiagnosticServiceFake();
        SubmitRequest req = new SubmitRequest();
        req.setStudentId(2L);
        req.setCourseId(5L);
        new DiagnosticController(diag, new DiagnosticResultServiceFake(), guard).submit(req);

        assertThat(guard.seenStudentId).isEqualTo(2L);
        assertThat(guard.seenCourseId).isEqualTo(5L);
        assertThat(diag.submitCalled).isTrue();
        assertThat(diag.submitStudentId).isEqualTo(2L);
    }

    @Test
    void diagnosticSubmit_omittedStudentId_usesPrincipal() {
        CurrentUser.set(2L);
        GuardSpy guard = new GuardSpy();
        DiagnosticServiceFake diag = new DiagnosticServiceFake();
        SubmitRequest req = new SubmitRequest();
        req.setCourseId(5L);
        new DiagnosticController(diag, new DiagnosticResultServiceFake(), guard).submit(req);

        assertThat(diag.submitStudentId).isEqualTo(2L);
        assertThat(guard.seenStudentId).isEqualTo(2L);
    }

    @Test
    void diagnosticSubmit_mismatchedUser_throwsForbidden_shortCircuits() {
        CurrentUser.set(9L);
        GuardSpy guard = new GuardSpy();
        DiagnosticServiceFake diag = new DiagnosticServiceFake();
        SubmitRequest req = new SubmitRequest();
        req.setStudentId(2L);
        req.setCourseId(5L);
        DiagnosticController c = new DiagnosticController(diag, new DiagnosticResultServiceFake(), guard);

        assertThatThrownBy(() -> c.submit(req)).isInstanceOf(BusinessException.class);
        assertThat(guard.called).isFalse();
        assertThat(diag.submitCalled).isFalse();
    }

    // ---- PathController ----

    @Test
    void pathCurrent_guardsWithStudentAndCourse() {
        CurrentUser.set(2L);
        GuardSpy guard = new GuardSpy();
        PathServiceFake path = new PathServiceFake();
        new PathController(path, guard).current(2L, 5L);

        assertThat(guard.seenStudentId).isEqualTo(2L);
        assertThat(guard.seenCourseId).isEqualTo(5L);
        assertThat(path.currentCalled).isTrue();
    }

    @Test
    void pathGenerate_guardsWithBodyStudentAndCourse_shortCircuitsOnReject() {
        CurrentUser.set(2L);
        GuardSpy guard = new GuardSpy(true);
        PathServiceFake path = new PathServiceFake();
        PathController c = new PathController(path, guard);
        PathGenerateRequest req = new PathGenerateRequest();
        req.setStudentId(2L);
        req.setCourseId(5L);

        assertThatThrownBy(() -> c.generate(req)).isInstanceOf(BusinessException.class);
        assertThat(path.generateCalled).isFalse();
    }

    @Test
    void pathGenerate_omittedStudentId_usesPrincipal() {
        CurrentUser.set(2L);
        GuardSpy guard = new GuardSpy();
        PathServiceFake path = new PathServiceFake();
        PathGenerateRequest req = new PathGenerateRequest();
        req.setCourseId(5L);
        new PathController(path, guard).generate(req);

        assertThat(path.generateCalled).isTrue();
        assertThat(path.generateStudentId).isEqualTo(2L);
        assertThat(guard.seenStudentId).isEqualTo(2L);
    }

    @Test
    void pathGenerate_mismatchedUser_throwsForbidden_shortCircuits() {
        CurrentUser.set(9L);
        GuardSpy guard = new GuardSpy();
        PathServiceFake path = new PathServiceFake();
        PathController c = new PathController(path, guard);
        PathGenerateRequest req = new PathGenerateRequest();
        req.setStudentId(2L);
        req.setCourseId(5L);

        assertThatThrownBy(() -> c.generate(req)).isInstanceOf(BusinessException.class);
        assertThat(guard.called).isFalse();
        assertThat(path.generateCalled).isFalse();
    }

    // ---- GrowthController ----

    @Test
    void growth_guardsWithStudentAndCourse() {
        CurrentUser.set(2L);
        GuardSpy guard = new GuardSpy();
        GrowthServiceFake growth = new GrowthServiceFake();
        new GrowthController(growth, guard).getGrowth(2L, 5L);

        assertThat(guard.seenStudentId).isEqualTo(2L);
        assertThat(guard.seenCourseId).isEqualTo(5L);
        assertThat(growth.called).isTrue();
    }

    @Test
    void growth_teacherWithOwnId_throwsForbidden() {
        CurrentUser.set(new CurrentPrincipal(2L, UserRole.TEACHER));
        GuardSpy guard = new GuardSpy();
        GrowthServiceFake growth = new GrowthServiceFake();
        GrowthController c = new GrowthController(growth, guard);

        assertThatThrownBy(() -> c.getGrowth(2L, 5L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.FORBIDDEN.getCode()));
        assertThat(guard.called).isFalse();
        assertThat(growth.called).isFalse();
    }
}
