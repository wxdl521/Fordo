package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不连库，只解析 LearningDataResetMapper / SysConfigMapper 的注解 SQL：每条语句只按 course_id 定位、
 * 子表经父表 JOIN、选课只置空 current_path_id 不删行；配置 upsert 按主键覆盖。
 *
 * <p>真实执行（按服务里的顺序在带外键的库上跑通、其它课程不受影响）已在本地临时库手工验证。</p>
 */
class LearningDataResetSqlTest {

    private static Configuration cfg;

    @BeforeAll
    static void parseMappers() {
        cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(LearningDataResetMapper.class);
        cfg.addMapper(SysConfigMapper.class);
    }

    @Test
    @DisplayName("单表删除：DELETE FROM <表> WHERE course_id = ?，参数是课程 id")
    void singleTableDeletes() {
        Map<String, String> expected = Map.of(
                "deleteAnswerRecords", "answer_record",
                "deleteDiagnosticSessions", "diagnostic_session",
                "deleteAdaptiveDiagSessions", "adaptive_diag_session",
                "deletePracticeSessions", "practice_session",
                "deleteLearningPaths", "learning_path",
                "deleteStudentMastery", "student_mastery",
                "deleteMasterySnapshots", "mastery_snapshot",
                "deleteCompanionConversations", "companion_conversation",
                "deleteStudentMemory", "student_memory",
                "deleteAgentPlans", "agent_plan");
        expected.forEach((method, table) -> {
            String id = "com.wenjin.mapper.LearningDataResetMapper." + method;
            assertThat(type(id)).as(method).isEqualTo(SqlCommandType.DELETE);
            assertThat(sql(id)).as(method).isEqualTo("DELETE FROM " + table + " WHERE course_id = ?");
        });
    }

    @Test
    @DisplayName("子表经父表 JOIN 按父表 course_id 删除（与外键指向的父行一致）")
    void childDeletesJoinParent() {
        assertThat(sql("com.wenjin.mapper.LearningDataResetMapper.deleteDiagnosticSessionQuestions"))
                .isEqualTo("DELETE q FROM diagnostic_session_question q JOIN diagnostic_session s "
                        + "ON s.id = q.session_id WHERE s.course_id = ?");
        assertThat(sql("com.wenjin.mapper.LearningDataResetMapper.deleteLearningPathItems"))
                .isEqualTo("DELETE i FROM learning_path_item i JOIN learning_path p "
                        + "ON p.id = i.path_id WHERE p.course_id = ?");
        assertThat(sql("com.wenjin.mapper.LearningDataResetMapper.deleteCompanionMessages"))
                .isEqualTo("DELETE m FROM companion_message m JOIN companion_conversation c "
                        + "ON c.id = m.conversation_id WHERE c.course_id = ?");
        assertThat(sql("com.wenjin.mapper.LearningDataResetMapper.deleteAgentPlanTasks"))
                .isEqualTo("DELETE t FROM agent_plan_task t JOIN agent_plan p "
                        + "ON p.id = t.plan_id WHERE p.course_id = ?");
    }

    @Test
    @DisplayName("选课保留：只 UPDATE student_course 置空 current_path_id，不是 DELETE")
    void studentCourseIsOnlyUpdated() {
        String id = "com.wenjin.mapper.LearningDataResetMapper.clearCurrentPaths";
        assertThat(type(id)).isEqualTo(SqlCommandType.UPDATE);
        assertThat(sql(id)).isEqualTo("UPDATE student_course SET current_path_id = NULL "
                + "WHERE course_id = ? AND current_path_id IS NOT NULL");
    }

    @Test
    @DisplayName("sys_config upsert：按主键 INSERT ... ON DUPLICATE KEY UPDATE，并刷新 updated_at")
    void sysConfigUpsert() {
        MappedStatement ms = cfg.getMappedStatement("com.wenjin.mapper.SysConfigMapper.upsert");
        Map<String, Object> arg = new HashMap<>(Map.of("key", "wenjin.ai.model", "value", "m", "encrypted", 0));
        arg.put("updatedBy", 1L);
        BoundSql bs = ms.getBoundSql(arg);
        String text = bs.getSql().replaceAll("\\s+", " ").trim();
        assertThat(text).startsWith("INSERT INTO sys_config (config_key, config_value, encrypted, updated_by, updated_at)")
                .contains("ON DUPLICATE KEY UPDATE config_value = ?, encrypted = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP");
        assertThat(bs.getParameterMappings()).extracting(p -> p.getProperty())
                .containsExactly("key", "value", "encrypted", "updatedBy", "value", "encrypted", "updatedBy");
    }

    private static String sql(String id) {
        MappedStatement ms = cfg.getMappedStatement(id);
        BoundSql bs = ms.getBoundSql(new HashMap<>(Map.of("courseId", 5L)));
        assertThat(bs.getParameterMappings()).extracting(p -> p.getProperty()).containsOnly("courseId");
        return bs.getSql().replaceAll("\\s+", " ").trim();
    }

    private static SqlCommandType type(String id) {
        return cfg.getMappedStatement(id).getSqlCommandType();
    }
}
