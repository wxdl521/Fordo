package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.wenjin.dto.AdminCourseQuery;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不连库，只用 MyBatis 解析管理后台课程 / 班级相关的 Mapper（XML 与注解 SQL），断言生成的 SQL 形状与参数：
 * 计数是一条 SQL 内的 GROUP BY 子查询（不 N+1）、筛选条件按参数拼接、补选课是一条 INSERT IGNORE ... SELECT。
 *
 * <p>真实执行已在本地临时库手工验证；这里守住 XML 能被解析、动态条件不写错。</p>
 */
class AdminClazzCourseSqlTest {

    private static Configuration cfg;

    @BeforeAll
    static void parseMappers() throws Exception {
        cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        for (String resource : List.of("mapper/AdminCourseMapper.xml", "mapper/AdminClazzMapper.xml")) {
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
            }
        }
        cfg.addMapper(StudentCourseMapper.class);
        cfg.addMapper(ClazzStudentMapper.class);
        cfg.addMapper(ClazzMapper.class);
    }

    // ───────────────────────── AdminCourseMapper ─────────────────────────

    @Test
    @DisplayName("课程分页：教师名 + 节点 / 学生 / 班级三个计数在同一条 SQL 内 GROUP BY 取齐，LIMIT 走参数")
    void coursePage_countsInOneStatement() {
        Sql sql = bound("com.wenjin.mapper.AdminCourseMapper.selectCoursePage", Map.of("q", query(null, null, null, null)));

        assertThat(sql.text).contains("LEFT JOIN sys_user u ON u.id = c.teacher_id");
        assertThat(count(sql.text, "GROUP BY course_id")).isEqualTo(3);
        assertThat(sql.text).contains("FROM kg_node").contains("FROM student_course").contains("FROM clazz_course");
        assertThat(sql.text).contains("ORDER BY c.id DESC").contains("LIMIT ?, ?");
        // 无筛选时没有外层 WHERE（kg_node 子查询自带的 WHERE 除外）
        assertThat(sql.text).doesNotContain("WHERE c.").doesNotContain("WHERE (");
        assertThat(sql.params).containsExactly(40L, 20);
    }

    @Test
    @DisplayName("课程筛选：keyword 匹配名称或编码、status、teacherId；archived=true 仅归档 / false 仅未归档 / null 不筛")
    void courseFilters() {
        Sql all = bound("com.wenjin.mapper.AdminCourseMapper.countCourses",
                Map.of("q", query("a\\_b", 1, null, 9L)));
        assertThat(all.text).contains("c.name LIKE CONCAT('%', ?, '%') OR c.code LIKE CONCAT('%', ?, '%')")
                .contains("c.status = ?")
                .contains("c.teacher_id = ?")
                .doesNotContain("archived_at");
        assertThat(all.params).containsExactly("a\\_b", "a\\_b", 1, 9L);
        // 计数不需要 JOIN 计数子查询
        assertThat(all.text).doesNotContain("GROUP BY");

        Sql archived = bound("com.wenjin.mapper.AdminCourseMapper.countCourses",
                Map.of("q", query(null, null, true, null)));
        assertThat(archived.text).contains("c.archived_at IS NOT NULL").doesNotContain("archived_at IS NULL");

        Sql live = bound("com.wenjin.mapper.AdminCourseMapper.selectCoursePage",
                Map.of("q", query(null, null, false, null)));
        assertThat(live.text).contains("c.archived_at IS NULL").doesNotContain("c.archived_at IS NOT NULL");
    }

    @Test
    @DisplayName("单个课程：同一段 SELECT（含计数）按 id 取，含归档课程")
    void courseById() {
        Sql sql = bound("com.wenjin.mapper.AdminCourseMapper.selectCourseById", Map.of("id", 5L));

        assertThat(count(sql.text, "GROUP BY course_id")).isEqualTo(3);
        assertThat(sql.text).contains("WHERE c.id = ?").doesNotContain("c.archived_at IS");
        assertThat(sql.params).containsExactly(5L);
    }

    // ───────────────────────── AdminClazzMapper ─────────────────────────

    @Test
    @DisplayName("班级分页：人数 / 课程数一条 SQL GROUP BY 取齐；keyword 为空不加条件")
    void clazzPage() {
        Map<String, Object> p = new HashMap<>();
        p.put("keyword", null);
        p.put("offset", 0L);
        p.put("size", 20);
        Sql noKeyword = bound("com.wenjin.mapper.AdminClazzMapper.selectClazzPage", p);
        assertThat(count(noKeyword.text, "GROUP BY clazz_id")).isEqualTo(2);
        assertThat(noKeyword.text).doesNotContain("LIKE").contains("ORDER BY z.id DESC").contains("LIMIT ?, ?");
        assertThat(noKeyword.params).containsExactly(0L, 20);

        p.put("keyword", "2201");
        Sql withKeyword = bound("com.wenjin.mapper.AdminClazzMapper.selectClazzPage", p);
        assertThat(withKeyword.text).contains("z.name LIKE CONCAT('%', ?, '%')");
        assertThat(withKeyword.params).containsExactly("2201", 0L, 20);

        Sql count = bound("com.wenjin.mapper.AdminClazzMapper.countClazzes", Map.of("keyword", "2201"));
        assertThat(count.text).contains("SELECT COUNT(*) FROM clazz z").contains("LIKE");
    }

    @Test
    @DisplayName("班级详情：成员只取 sys_user 基本字段（无密码）；关联课程带 archived 派生列")
    void clazzDetailQueries() {
        Sql members = bound("com.wenjin.mapper.AdminClazzMapper.selectMembers", Map.of("clazzId", 100L));
        assertThat(members.text).contains("u.id, u.username, u.real_name, u.status").doesNotContain("password");
        assertThat(members.params).containsExactly(100L);

        Sql courses = bound("com.wenjin.mapper.AdminClazzMapper.selectLinkedCourses", Map.of("clazzId", 100L));
        assertThat(courses.text).contains("(c.archived_at IS NOT NULL) AS archived").contains("cc.clazz_id = ?");
    }

    // ───────────────────────── 补选课 / 成员批量写 ─────────────────────────

    @Test
    @DisplayName("班级关联课程补选：一条 INSERT IGNORE INTO student_course ... SELECT，只补学生角色成员")
    void enrollClazzMembersInCourse_singleInsertIgnoreSelect() {
        Sql sql = bound("com.wenjin.mapper.StudentCourseMapper.enrollClazzMembersInCourse",
                Map.of("clazzId", 100L, "courseId", 5L));

        assertThat(statementType("com.wenjin.mapper.StudentCourseMapper.enrollClazzMembersInCourse"))
                .isEqualTo(SqlCommandType.INSERT);
        assertThat(sql.text).startsWith("INSERT IGNORE INTO student_course (student_id, course_id)")
                .contains("SELECT cs.student_id, ?")
                .contains("FROM clazz_student cs")
                .contains("u.role = 2")
                .contains("cs.clazz_id = ?");
        assertThat(sql.params).containsExactly(5L, 100L);
    }

    @Test
    @DisplayName("学生入班补选：一条 INSERT IGNORE ... SELECT 覆盖班级全部关联课程 × 这批学生")
    void enrollStudentsInClazzCourses_singleInsertIgnoreSelect() {
        Sql sql = bound("com.wenjin.mapper.StudentCourseMapper.enrollStudentsInClazzCourses",
                Map.of("clazzId", 100L, "studentIds", List.of(3L, 4L)));

        assertThat(sql.text).startsWith("INSERT IGNORE INTO student_course (student_id, course_id)")
                .contains("SELECT cs.student_id, cc.course_id")
                .contains("JOIN clazz_course cc ON cc.clazz_id = cs.clazz_id")
                .contains("cs.student_id IN");
        assertThat(sql.compact()).endsWith("IN(?,?)");
        assertThat(sql.params).containsExactly(100L, 3L, 4L);
    }

    @Test
    @DisplayName("成员批量插入 / 转班各是一条 SQL")
    void memberBatchWrites() {
        Sql insert = bound("com.wenjin.mapper.ClazzStudentMapper.insertBatch",
                Map.of("clazzId", 100L, "studentIds", List.of(3L, 4L)));
        assertThat(insert.text).contains("INSERT INTO clazz_student (clazz_id, student_id) VALUES");
        assertThat(insert.compact()).endsWith("VALUES(?,?),(?,?)");
        assertThat(insert.params).containsExactly(100L, 3L, 100L, 4L);

        Sql move = bound("com.wenjin.mapper.ClazzStudentMapper.moveToClazz",
                Map.of("clazzId", 100L, "studentIds", List.of(5L)));
        assertThat(move.text).contains("UPDATE clazz_student SET clazz_id = ?").contains("student_id IN");
        assertThat(move.params).containsExactly(100L, 5L);

        Sql lock = bound("com.wenjin.mapper.ClazzMapper.selectByIdForUpdate", Map.of("id", 100L));
        assertThat(lock.text).contains("FOR UPDATE");
    }

    // ───────────────────────── helpers ─────────────────────────

    private record Sql(String text, List<Object> params) {
        /** 去掉全部空白，便于断言 foreach 展开结果。 */
        String compact() {
            return text.replaceAll("\\s+", "");
        }
    }

    private static Sql bound(String statementId, Map<String, Object> params) {
        MappedStatement ms = cfg.getMappedStatement(statementId);
        Map<String, Object> arg = new HashMap<>(params);
        BoundSql bs = ms.getBoundSql(arg);
        List<Object> values = new ArrayList<>();
        MetaObject meta = cfg.newMetaObject(arg);
        for (ParameterMapping pm : bs.getParameterMappings()) {
            String prop = pm.getProperty();
            values.add(bs.hasAdditionalParameter(prop) ? bs.getAdditionalParameter(prop) : meta.getValue(prop));
        }
        return new Sql(bs.getSql().replaceAll("\\s+", " ").trim(), values);
    }

    private static SqlCommandType statementType(String statementId) {
        return cfg.getMappedStatement(statementId).getSqlCommandType();
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static AdminCourseQuery query(String keyword, Integer status, Boolean archived, Long teacherId) {
        AdminCourseQuery q = new AdminCourseQuery();
        q.setKeyword(keyword);
        q.setStatus(status);
        q.setArchived(archived);
        q.setTeacherId(teacherId);
        q.setOffset(40L);
        q.setSize(20);
        return q;
    }
}
