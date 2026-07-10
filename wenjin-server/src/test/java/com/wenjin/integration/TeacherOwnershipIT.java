package com.wenjin.integration;

import com.wenjin.common.BusinessException;
import com.wenjin.config.CurrentUser;
import com.wenjin.entity.Course;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.TeacherGraphService;
import com.wenjin.support.ReviewMarker;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 教师课程所有权隔离 — 真实 MySQL（Testcontainers）集成测试。
 *
 * <p>验证 JOIN 归属：teacher A 对 teacher B 的 edge 统一 404；owner 可 accept。
 */
@SpringBootTest
@ActiveProfiles("it")
class TeacherOwnershipIT extends MySqlContainerSupport {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("WENJIN_AUTH_SECRET", () -> "it-test-secret-not-for-prod");
        registry.add("wenjin.auth.secret", () -> "it-test-secret-not-for-prod");
    }

    @Autowired private TeacherGraphService teacherGraphService;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private KgNodeMapper kgNodeMapper;
    @Autowired private KgEdgeMapper kgEdgeMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long teacherAId;
    private long teacherBId;
    private long edgeOnCourseB;

    @BeforeEach
    void seedTenants() {
        CurrentUser.clear();
        // 清理业务表数据，保留 Flyway 建好的结构
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[]{
                "kg_edge", "kg_node", "course", "sys_user"
        }) {
            jdbcTemplate.execute("DELETE FROM `" + table + "`");
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");

        SysUser a = new SysUser();
        a.setUsername("it_teacher_a");
        a.setPassword("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012");
        a.setRole(1);
        a.setStatus(1);
        sysUserMapper.insert(a);
        teacherAId = a.getId();

        SysUser b = new SysUser();
        b.setUsername("it_teacher_b");
        b.setPassword("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012");
        b.setRole(1);
        b.setStatus(1);
        sysUserMapper.insert(b);
        teacherBId = b.getId();

        Course courseA = new Course();
        courseA.setCode("IT_COURSE_A");
        courseA.setName("Course A");
        courseA.setTeacherId(teacherAId);
        courseA.setStatus(1);
        courseMapper.insert(courseA);

        Course courseB = new Course();
        courseB.setCode("IT_COURSE_B");
        courseB.setName("Course B");
        courseB.setTeacherId(teacherBId);
        courseB.setStatus(1);
        courseMapper.insert(courseB);

        KgNode from = new KgNode();
        from.setCourseId(courseB.getId());
        from.setNodeCode("N1");
        from.setName("Node 1");
        from.setIsKey(0);
        kgNodeMapper.insert(from);

        KgNode to = new KgNode();
        to.setCourseId(courseB.getId());
        to.setNodeCode("N2");
        to.setName("Node 2");
        to.setIsKey(0);
        kgNodeMapper.insert(to);

        KgEdge edge = new KgEdge();
        edge.setCourseId(courseB.getId());
        edge.setFromNodeId(from.getId());
        edge.setToNodeId(to.getId());
        edge.setRelationType(1);
        edge.setRelationNote(ReviewMarker.PREFIX + "：AI 建议");
        edge.setConfidence(80);
        kgEdgeMapper.insert(edge);
        edgeOnCourseB = edge.getId();
    }

    @AfterEach
    void clearUser() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("跨租户：teacher A accept teacher B 的 edge → 404")
    void teacherCannotAcceptEdgeFromAnotherTeachersCourse_realDb() {
        CurrentUser.set(new CurrentPrincipal(teacherAId, UserRole.TEACHER));

        assertThatThrownBy(() -> teacherGraphService.acceptEdge(edgeOnCourseB))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(404);

        KgEdge stillPending = kgEdgeMapper.selectById(edgeOnCourseB);
        assertThat(stillPending.getRelationNote()).startsWith(ReviewMarker.PREFIX);
    }

    @Test
    @DisplayName("owner：teacher B 可 accept 本课 edge")
    void ownerCanAcceptOwnEdge_realDb() {
        CurrentUser.set(new CurrentPrincipal(teacherBId, UserRole.TEACHER));

        assertThatCode(() -> teacherGraphService.acceptEdge(edgeOnCourseB))
                .doesNotThrowAnyException();

        KgEdge accepted = kgEdgeMapper.selectById(edgeOnCourseB);
        assertThat(ReviewMarker.isPending(accepted.getRelationNote())).isFalse();
    }
}
