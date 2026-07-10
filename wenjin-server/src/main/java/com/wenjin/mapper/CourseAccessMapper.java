package com.wenjin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 课程归属解析：把 course / node / edge / question 解析到课程并校验 teacher_id。
 * 单条 JOIN/查询，供 {@link com.wenjin.security.CourseAccessService} 统一鉴权。
 */
@Mapper
public interface CourseAccessMapper {

    @Select("SELECT CASE WHEN COUNT(*) > 0 THEN 1 ELSE 0 END FROM course WHERE id = #{courseId}")
    boolean courseExists(@Param("courseId") Long courseId);

    @Select("SELECT id FROM course WHERE code = #{courseCode} LIMIT 1")
    Long courseIdByCode(@Param("courseCode") String courseCode);

    /**
     * 课程是否归属于指定教师（teacher_id 匹配）。
     * 课程不存在或 teacher_id 为 NULL 时返回 false。
     */
    @Select("""
            SELECT CASE WHEN COUNT(*) > 0 THEN 1 ELSE 0 END
            FROM course
            WHERE id = #{courseId} AND teacher_id = #{teacherId}
            """)
    boolean isOwnedCourse(@Param("courseId") Long courseId, @Param("teacherId") Long teacherId);

    @Select("SELECT course_id FROM kg_node WHERE id = #{nodeId} LIMIT 1")
    Long courseIdForNode(@Param("nodeId") Long nodeId);

    @Select("SELECT course_id FROM kg_edge WHERE id = #{edgeId} LIMIT 1")
    Long courseIdForEdge(@Param("edgeId") Long edgeId);

    @Select("SELECT course_id FROM question WHERE id = #{questionId} LIMIT 1")
    Long courseIdForQuestion(@Param("questionId") Long questionId);

    /**
     * 批量解析题目所属课程 ID（与入参顺序无关；缺失 id 不出现在结果中）。
     */
    @Select("""
            <script>
            SELECT course_id FROM question
            WHERE id IN
            <foreach collection="questionIds" item="id" open="(" separator="," close=")">
              #{id}
            </foreach>
            </script>
            """)
    List<Long> courseIdsForQuestions(@Param("questionIds") Collection<Long> questionIds);

    /**
     * 统计 ids 中属于指定课程的题目数（用于批量审核归属校验）。
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM question
            WHERE course_id = #{courseId}
              AND id IN
            <foreach collection="questionIds" item="id" open="(" separator="," close=")">
              #{id}
            </foreach>
            </script>
            """)
    long countQuestionsInCourse(@Param("courseId") Long courseId,
                                @Param("questionIds") Collection<Long> questionIds);
}
