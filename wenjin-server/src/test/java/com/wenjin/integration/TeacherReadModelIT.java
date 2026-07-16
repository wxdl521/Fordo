package com.wenjin.integration;

import com.wenjin.config.CurrentUser;
import com.wenjin.dto.DashboardVO;
import com.wenjin.dto.QuestionStatusCount;
import com.wenjin.dto.TeacherQuestionPageVO;
import com.wenjin.entity.Course;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.StudentCourse;
import com.wenjin.entity.StudentMastery;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.StudentCourseMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.mapper.TeacherDashboardMapper;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.TeacherDashboardService;
import com.wenjin.service.TeacherQuestionService;
import com.wenjin.support.QuestionStatus;
import com.wenjin.support.StemHash;
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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 教师读模型集成测试：roster 口径看板 + 题库 SQL 分页/状态 GROUP BY。
 * <p>依赖 Testcontainers MySQL；Docker 不可用时本类会启动失败（单元测试仍须绿）。
 */
@SpringBootTest
@ActiveProfiles("it")
class TeacherReadModelIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("WENJIN_AUTH_SECRET", () -> "it-test-secret-not-for-prod-at-least-32-bytes");
        registry.add("wenjin.auth.secret", () -> "it-test-secret-not-for-prod-at-least-32-bytes");
        // 关闭衰减，便于用 raw mastery_level 断言
        registry.add("wenjin.mastery.decay.enabled", () -> "false");
    }

    @Autowired private TeacherDashboardService dashboardService;
    @Autowired private TeacherQuestionService questionService;
    @Autowired private TeacherDashboardMapper dashboardMapper;
    @Autowired private QuestionMapper questionMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private KgNodeMapper kgNodeMapper;
    @Autowired private StudentCourseMapper studentCourseMapper;
    @Autowired private StudentMasteryMapper studentMasteryMapper;
    @Autowired private QuestionNodeMapper questionNodeMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long teacherId;
    private long courseId;
    private long nodeAId;
    private long nodeBId;
    private long student1Id;
    private long student2Id;
    private long ghostId;

    @BeforeEach
    void seed() {
        CurrentUser.clear();
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[]{
                "diagnostic_session", "adaptive_diag_session",
                "question_node", "question_option", "question",
                "student_mastery", "student_course",
                "kg_node", "course", "sys_user"
        }) {
            try {
                jdbcTemplate.execute("DELETE FROM `" + table + "`");
            } catch (Exception ignored) {
                // partial schema
            }
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");

        SysUser teacher = user("it_read_teacher", 1);
        teacherId = teacher.getId();

        Course course = new Course();
        course.setCode("IT_READ_MODEL");
        course.setName("Read Model Course");
        course.setTeacherId(teacherId);
        course.setStatus(1);
        courseMapper.insert(course);
        courseId = course.getId();

        KgNode nodeA = node(courseId, "N_A", "节点A", "章1");
        KgNode nodeB = node(courseId, "N_B", "节点B", "章1");
        nodeAId = nodeA.getId();
        nodeBId = nodeB.getId();

        SysUser s1 = user("it_read_s1", 2);
        SysUser s2 = user("it_read_s2", 2);
        SysUser ghost = user("it_read_ghost", 2);
        student1Id = s1.getId();
        student2Id = s2.getId();
        ghostId = ghost.getId();

        enroll(student1Id, courseId);
        enroll(student2Id, courseId);
        // ghost 未选课

        // s1 两节点掌握；s2 仅 nodeA；ghost 有 mastery 但不应进 roster
        mastery(student1Id, courseId, nodeAId, "90", 2);
        mastery(student1Id, courseId, nodeBId, "50", 1);
        mastery(student2Id, courseId, nodeAId, "60", 1);
        mastery(ghostId, courseId, nodeAId, "99", 2);

        // s1 完成固定诊断；s2 完成自适应；ghost 完成诊断不计
        jdbcTemplate.update(
                "INSERT INTO diagnostic_session (token, student_id, course_id, status) VALUES (?,?,?,1)",
                "tok-s1", student1Id, courseId);
        jdbcTemplate.update(
                "INSERT INTO adaptive_diag_session (student_id, course_id, state_json, status) VALUES (?,?,?,1)",
                student2Id, courseId, "{}");
        jdbcTemplate.update(
                "INSERT INTO diagnostic_session (token, student_id, course_id, status) VALUES (?,?,?,1)",
                "tok-ghost", ghostId, courseId);

        // 题目：3 条不同状态 + 主考点
        long q1 = insertQuestion(courseId, "stem-1", 90, QuestionStatus.PENDING);
        long q2 = insertQuestion(courseId, "stem-2", 75, QuestionStatus.APPROVED);
        long q3 = insertQuestion(courseId, "stem-3", 60, QuestionStatus.REJECTED);
        linkMain(q1, nodeAId);
        linkMain(q2, nodeBId);
        linkMain(q3, nodeAId);
    }

    @AfterEach
    void clear() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("roster：totalStudents=选课数；ghost mastery 排除；diagnosed 计完成诊断的选课生")
    void dashboardUsesRosterDenominator() {
        CurrentUser.set(new CurrentPrincipal(teacherId, UserRole.TEACHER));

        assertThat(dashboardMapper.countEnrolled(courseId)).isEqualTo(2);
        assertThat(dashboardMapper.countDiagnosed(courseId)).isEqualTo(2);

        List<StudentMastery> enrolled = dashboardMapper.selectEnrolledMastery(courseId);
        assertThat(enrolled).hasSize(3); // s1×2 + s2×1；ghost 排除
        assertThat(enrolled).noneMatch(m -> m.getStudentId().equals(ghostId));

        DashboardVO vo = dashboardService.dashboard(courseId);

        assertThat(vo.getSummary().getTotalStudents()).isEqualTo(2);
        assertThat(vo.getSummary().getDiagnosedStudents()).isEqualTo(2);
        assertThat(vo.getSummary().getNodeCount()).isEqualTo(2);

        DashboardVO.NodeStat na = vo.getNodes().stream()
                .filter(n -> "N_A".equals(n.getNodeCode())).findFirst().orElseThrow();
        // s1 mastered + s2 weak；undiagnosed = 0
        assertThat(na.getMastered()).isEqualTo(1);
        assertThat(na.getWeak()).isEqualTo(1);
        assertThat(na.getUndiagnosed()).isEqualTo(0);

        DashboardVO.NodeStat nb = vo.getNodes().stream()
                .filter(n -> "N_B".equals(n.getNodeCode())).findFirst().orElseThrow();
        // 仅 s1 weak；s2 undiagnosed
        assertThat(nb.getWeak()).isEqualTo(1);
        assertThat(nb.getUndiagnosed()).isEqualTo(1);

        // mastered cells = 1；denom = 2*2 = 4 → 0.25
        assertThat(vo.getSummary().getClassAvgRate()).isCloseTo(0.25, within(0.001));
    }

    @Test
    @DisplayName("题库：LIMIT/OFFSET 下推 + status GROUP BY + nodeCode JOIN")
    void questionPaginationAndStatusCountsHitDatabase() {
        CurrentUser.set(new CurrentPrincipal(teacherId, UserRole.TEACHER));

        List<Question> page1 = questionMapper.selectTeacherPage(courseId, null, null, null, 0, 2);
        assertThat(page1).hasSize(2);
        // confidence desc: 90, 75
        assertThat(page1.get(0).getConfidence()).isEqualTo(90);
        assertThat(page1.get(1).getConfidence()).isEqualTo(75);

        List<Question> page2 = questionMapper.selectTeacherPage(courseId, null, null, null, 2, 2);
        assertThat(page2).hasSize(1);
        assertThat(page2.get(0).getConfidence()).isEqualTo(60);

        assertThat(questionMapper.countTeacherFiltered(courseId, null, null, null)).isEqualTo(3L);
        assertThat(questionMapper.countTeacherFiltered(courseId, null, "N_A", null)).isEqualTo(2L);
        assertThat(questionMapper.countTeacherFiltered(courseId, null, null, "ge85")).isEqualTo(1L);

        List<QuestionStatusCount> grouped = questionMapper.countGroupedByStatus(courseId);
        assertThat(grouped).hasSize(3);

        TeacherQuestionPageVO vo = questionService.list(courseId, null, "N_A", null, 1, 10);
        assertThat(vo.getTotal()).isEqualTo(2L);
        assertThat(vo.getItems()).hasSize(2);
        assertThat(vo.getCounts().getPending()).isEqualTo(1L);
        assertThat(vo.getCounts().getPassed()).isEqualTo(1L);
        assertThat(vo.getCounts().getRejected()).isEqualTo(1L);

        int updated = questionMapper.updateStatusByFilter(
                courseId, QuestionStatus.APPROVED, QuestionStatus.PENDING, null, null);
        assertThat(updated).isEqualTo(1);
        assertThat(questionMapper.countTeacherFiltered(courseId, QuestionStatus.PENDING, null, null))
                .isEqualTo(0L);
    }

    private SysUser user(String username, int role) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012");
        u.setRole(role);
        u.setStatus(1);
        sysUserMapper.insert(u);
        return u;
    }

    private KgNode node(long courseId, String code, String name, String chapter) {
        KgNode n = new KgNode();
        n.setCourseId(courseId);
        n.setNodeCode(code);
        n.setName(name);
        n.setChapter(chapter);
        n.setIsKey(1);
        kgNodeMapper.insert(n);
        return n;
    }

    private void enroll(long studentId, long courseId) {
        StudentCourse sc = new StudentCourse();
        sc.setStudentId(studentId);
        sc.setCourseId(courseId);
        studentCourseMapper.insert(sc);
    }

    private void mastery(long studentId, long courseId, long nodeId, String score, int level) {
        StudentMastery m = new StudentMastery();
        m.setStudentId(studentId);
        m.setCourseId(courseId);
        m.setNodeId(nodeId);
        m.setMasteryScore(new BigDecimal(score));
        m.setMasteryLevel(level);
        studentMasteryMapper.insert(m);
    }

    private long insertQuestion(long courseId, String stem, int conf, int status) {
        Question q = new Question();
        q.setCourseId(courseId);
        q.setStem(stem);
        q.setStemHash(StemHash.sha256Hex(stem));
        q.setType(1);
        q.setDifficulty(3);
        q.setAnswer("A");
        q.setSource(1);
        q.setStatus(status);
        q.setConfidence(conf);
        questionMapper.insert(q);
        return q.getId();
    }

    private void linkMain(long questionId, long nodeId) {
        QuestionNode qn = new QuestionNode();
        qn.setQuestionId(questionId);
        qn.setNodeId(nodeId);
        qn.setWeight(1);
        questionNodeMapper.insert(qn);
    }
}
