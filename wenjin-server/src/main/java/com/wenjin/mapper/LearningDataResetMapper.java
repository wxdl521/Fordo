package com.wenjin.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 按课程清空学生学习数据（管理后台「数据维护」）。每个方法一条 SQL，返回受影响行数；
 * 调用顺序（子表先删）由 {@code AdminDataResetServiceImpl} 在单事务内保证，清单与外键依赖见该类注释。
 *
 * <p>子表经父表 JOIN 定位课程（learning_path_item / diagnostic_session_question / companion_message /
 * agent_plan_task），与外键实际指向的父行严格一致。</p>
 */
@Mapper
public interface LearningDataResetMapper {

    @Delete("DELETE FROM answer_record WHERE course_id = #{courseId}")
    int deleteAnswerRecords(@Param("courseId") Long courseId);

    @Delete("""
            DELETE q FROM diagnostic_session_question q
            JOIN diagnostic_session s ON s.id = q.session_id
            WHERE s.course_id = #{courseId}
            """)
    int deleteDiagnosticSessionQuestions(@Param("courseId") Long courseId);

    @Delete("DELETE FROM diagnostic_session WHERE course_id = #{courseId}")
    int deleteDiagnosticSessions(@Param("courseId") Long courseId);

    @Delete("DELETE FROM adaptive_diag_session WHERE course_id = #{courseId}")
    int deleteAdaptiveDiagSessions(@Param("courseId") Long courseId);

    @Delete("DELETE FROM practice_session WHERE course_id = #{courseId}")
    int deletePracticeSessions(@Param("courseId") Long courseId);

    /** 选课保留，只断开它对学习路径的引用（fk_sc_current_path），否则 learning_path 删不掉。 */
    @Update("""
            UPDATE student_course SET current_path_id = NULL
            WHERE course_id = #{courseId} AND current_path_id IS NOT NULL
            """)
    int clearCurrentPaths(@Param("courseId") Long courseId);

    @Delete("""
            DELETE i FROM learning_path_item i
            JOIN learning_path p ON p.id = i.path_id
            WHERE p.course_id = #{courseId}
            """)
    int deleteLearningPathItems(@Param("courseId") Long courseId);

    @Delete("DELETE FROM learning_path WHERE course_id = #{courseId}")
    int deleteLearningPaths(@Param("courseId") Long courseId);

    @Delete("DELETE FROM student_mastery WHERE course_id = #{courseId}")
    int deleteStudentMastery(@Param("courseId") Long courseId);

    @Delete("DELETE FROM mastery_snapshot WHERE course_id = #{courseId}")
    int deleteMasterySnapshots(@Param("courseId") Long courseId);

    @Delete("""
            DELETE m FROM companion_message m
            JOIN companion_conversation c ON c.id = m.conversation_id
            WHERE c.course_id = #{courseId}
            """)
    int deleteCompanionMessages(@Param("courseId") Long courseId);

    @Delete("DELETE FROM companion_conversation WHERE course_id = #{courseId}")
    int deleteCompanionConversations(@Param("courseId") Long courseId);

    @Delete("DELETE FROM student_memory WHERE course_id = #{courseId}")
    int deleteStudentMemory(@Param("courseId") Long courseId);

    @Delete("""
            DELETE t FROM agent_plan_task t
            JOIN agent_plan p ON p.id = t.plan_id
            WHERE p.course_id = #{courseId}
            """)
    int deleteAgentPlanTasks(@Param("courseId") Long courseId);

    @Delete("DELETE FROM agent_plan WHERE course_id = #{courseId}")
    int deleteAgentPlans(@Param("courseId") Long courseId);

    @Delete("""
            DELETE m FROM teacher_message m
            JOIN teacher_conversation c ON c.id = m.conversation_id
            WHERE c.course_id = #{courseId}
            """)
    int deleteTeacherMessages(@Param("courseId") Long courseId);

    @Delete("DELETE FROM teacher_conversation WHERE course_id = #{courseId}")
    int deleteTeacherConversations(@Param("courseId") Long courseId);

    /** 教师助教 run 留作用量/监控，但步骤输入输出含学生姓名与学情，重置时清空（run 行与 token/耗时保留）。 */
    @Update("""
            UPDATE agent_step s JOIN agent_run r ON r.id = s.run_id
            SET s.input_json = NULL, s.output_json = NULL
            WHERE r.course_id = #{courseId} AND r.kind = 'TEACHER_CHAT'
            """)
    int clearTeacherChatStepIo(@Param("courseId") Long courseId);

    @Update("""
            UPDATE agent_run SET input_digest = NULL
            WHERE course_id = #{courseId} AND kind = 'TEACHER_CHAT' AND input_digest IS NOT NULL
            """)
    int clearTeacherChatRunDigest(@Param("courseId") Long courseId);
}
