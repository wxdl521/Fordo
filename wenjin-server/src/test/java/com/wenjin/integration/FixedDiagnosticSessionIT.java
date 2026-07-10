package com.wenjin.integration;

import com.wenjin.common.BusinessException;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.PaperVO;
import com.wenjin.dto.SubmitRequest;
import com.wenjin.dto.SubmitResult;
import com.wenjin.entity.Course;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.entity.StudentCourse;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.AnswerRecordMapper;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.mapper.StudentCourseMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.DiagnosticService;
import com.wenjin.support.QuestionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 固定诊断会话 — 真实 MySQL（Testcontainers）集成测试。
 * 覆盖：冻结提交、幂等重放、并发双提交仅一次掌握度记账。
 */
@SpringBootTest
@ActiveProfiles("it")
class FixedDiagnosticSessionIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "&useAffectedRows=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("WENJIN_AUTH_SECRET", () -> "it-test-secret-not-for-prod");
        registry.add("wenjin.auth.secret", () -> "it-test-secret-not-for-prod");
        registry.add("wenjin.diagnostic.paper-size", () -> "3");
    }

    @Autowired private DiagnosticService diagnosticService;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private StudentCourseMapper studentCourseMapper;
    @Autowired private KgNodeMapper kgNodeMapper;
    @Autowired private QuestionMapper questionMapper;
    @Autowired private QuestionOptionMapper questionOptionMapper;
    @Autowired private QuestionNodeMapper questionNodeMapper;
    @Autowired private AnswerRecordMapper answerRecordMapper;
    @Autowired private StudentMasteryMapper studentMasteryMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long studentId;
    private long otherStudentId;
    private long courseId;

    @BeforeEach
    void seed() {
        CurrentUser.clear();
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[]{
                "answer_record", "student_mastery", "mastery_snapshot",
                "diagnostic_session_question", "diagnostic_session",
                "question_option", "question_node", "question",
                "kg_node", "student_course", "course", "sys_user"
        }) {
            try {
                jdbcTemplate.execute("DELETE FROM `" + table + "`");
            } catch (Exception ignored) {
                // table may not exist if V3 not applied — fail later on assert
            }
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");

        SysUser student = new SysUser();
        student.setUsername("it_diag_student");
        student.setPassword("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012");
        student.setRole(2);
        student.setStatus(1);
        sysUserMapper.insert(student);
        studentId = student.getId();

        SysUser other = new SysUser();
        other.setUsername("it_diag_other");
        other.setPassword("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012");
        other.setRole(2);
        other.setStatus(1);
        sysUserMapper.insert(other);
        otherStudentId = other.getId();

        Course course = new Course();
        course.setCode("IT_DIAG_COURSE");
        course.setName("Diag Course");
        course.setStatus(1);
        courseMapper.insert(course);
        courseId = course.getId();

        StudentCourse sc = new StudentCourse();
        sc.setStudentId(studentId);
        sc.setCourseId(courseId);
        studentCourseMapper.insert(sc);

        StudentCourse sc2 = new StudentCourse();
        sc2.setStudentId(otherStudentId);
        sc2.setCourseId(courseId);
        studentCourseMapper.insert(sc2);

        KgNode node = new KgNode();
        node.setCourseId(courseId);
        node.setNodeCode("D1");
        node.setName("Diag Node");
        node.setChapter("章一");
        node.setIsKey(1);
        kgNodeMapper.insert(node);

        for (int i = 1; i <= 5; i++) {
            Question q = new Question();
            q.setCourseId(courseId);
            q.setStem("题干" + i);
            q.setType(1);
            q.setDifficulty(2);
            q.setStatus(QuestionStatus.APPROVED);
            questionMapper.insert(q);

            QuestionOption a = new QuestionOption();
            a.setQuestionId(q.getId());
            a.setOptionKey("A");
            a.setOptionText("正确");
            a.setIsCorrect(1);
            questionOptionMapper.insert(a);

            QuestionOption b = new QuestionOption();
            b.setQuestionId(q.getId());
            b.setOptionKey("B");
            b.setOptionText("错误");
            b.setIsCorrect(0);
            questionOptionMapper.insert(b);

            QuestionNode qn = new QuestionNode();
            qn.setQuestionId(q.getId());
            qn.setNodeId(node.getId());
            qn.setWeight(1);
            questionNodeMapper.insert(qn);
        }
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    private PaperVO composeAs(long sid) {
        CurrentUser.set(new CurrentPrincipal(sid, UserRole.STUDENT));
        try {
            return diagnosticService.composePaper(courseId);
        } finally {
            CurrentUser.clear();
        }
    }

    private SubmitRequest fullCorrect(PaperVO paper) {
        SubmitRequest req = new SubmitRequest();
        req.setStudentId(studentId);
        req.setCourseId(courseId);
        req.setSessionToken(paper.getSessionToken());
        List<SubmitRequest.Answer> answers = new ArrayList<>();
        for (var pq : paper.getQuestions()) {
            SubmitRequest.Answer a = new SubmitRequest.Answer();
            a.setQuestionId(pq.getQuestionId());
            a.setOptionKey("A");
            answers.add(a);
        }
        req.setAnswers(answers);
        return req;
    }

    @Test
    @DisplayName("IT：组卷返回 sessionToken；精确提交成功；重放同分不重复掌握度")
    void composeSubmitReplay_idempotentMastery() {
        PaperVO paper = composeAs(studentId);
        assertThat(paper.getSessionToken()).isNotBlank();
        assertThat(paper.getTotal()).isEqualTo(3);
        assertThat(paper.getQuestions()).allSatisfy(q ->
                assertThat(q.getOptions()).noneMatch(o -> o.getKey() == null));

        SubmitRequest req = fullCorrect(paper);
        SubmitResult first = diagnosticService.submit(req);
        assertThat(first.getTotal()).isEqualTo(3);
        assertThat(first.getCorrectCount()).isEqualTo(3);

        Long answerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM answer_record WHERE fixed_diagnostic_session_id IS NOT NULL",
                Long.class);
        assertThat(answerCount).isEqualTo(3);

        Long masteryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM student_mastery WHERE student_id = ?",
                Long.class, studentId);
        assertThat(masteryCount).isEqualTo(1);

        // 重放
        SubmitResult second = diagnosticService.submit(req);
        assertThat(second.getCorrectCount()).isEqualTo(first.getCorrectCount());
        assertThat(second.getTotal()).isEqualTo(first.getTotal());

        Long answerCountAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM answer_record WHERE fixed_diagnostic_session_id IS NOT NULL",
                Long.class);
        assertThat(answerCountAfter).isEqualTo(3);
    }

    @Test
    @DisplayName("IT：他人 token → FORBIDDEN")
    void otherUserSessionForbidden() {
        PaperVO paper = composeAs(studentId);
        SubmitRequest req = fullCorrect(paper);
        req.setStudentId(otherStudentId);

        assertThatThrownBy(() -> diagnosticService.submit(req))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("IT：会话外题 → BAD_REQUEST")
    void foreignQuestionRejected() {
        PaperVO paper = composeAs(studentId);
        SubmitRequest req = fullCorrect(paper);
        req.getAnswers().get(0).setQuestionId(999999L);

        assertThatThrownBy(() -> diagnosticService.submit(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("会话外");
    }

    @Test
    @DisplayName("IT：并发双提交仅写一套 answer_record / 一次掌握度")
    void concurrentDoubleSubmit_singleBooking() throws Exception {
        PaperVO paper = composeAs(studentId);
        SubmitRequest req = fullCorrect(paper);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        try {
            List<Future<SubmitResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    SubmitResult r = diagnosticService.submit(req);
                    success.incrementAndGet();
                    return r;
                }));
            }
            start.countDown();
            SubmitResult r1 = futures.get(0).get(30, TimeUnit.SECONDS);
            SubmitResult r2 = futures.get(1).get(30, TimeUnit.SECONDS);
            assertThat(r1.getCorrectCount()).isEqualTo(r2.getCorrectCount());
            assertThat(success.get()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }

        Long answerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM answer_record WHERE fixed_diagnostic_session_id IS NOT NULL",
                Long.class);
        assertThat(answerCount).isEqualTo(3);

        Long masteryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM student_mastery WHERE student_id = ?",
                Long.class, studentId);
        assertThat(masteryCount).isEqualTo(1);

        Map<String, Object> session = jdbcTemplate.queryForMap(
                "SELECT status FROM diagnostic_session WHERE token = ?", paper.getSessionToken());
        assertThat(((Number) session.get("status")).intValue()).isEqualTo(1);
    }
}
