package com.wenjin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
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

    /**
     * 获取课程写锁前清空当前 SqlSession 缓存：等待锁后执行的复授权必须重新查询数据库，
     * 不能复用锁前 course/node/edge 归属解析结果。
     */
    @Select("SELECT id FROM course WHERE id = #{courseId} AND archived_at IS NULL FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long lockActiveCourse(@Param("courseId") Long courseId);

    /** Restore-only lock that deliberately includes archived rows. */
    @Select("SELECT id FROM course WHERE id = #{courseId} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long lockCourseIncludingArchived(@Param("courseId") Long courseId);

    @Select("""
            SELECT CASE WHEN COUNT(*) > 0 THEN 1 ELSE 0 END
            FROM course
            WHERE id = #{courseId} AND archived_at IS NULL
            """)
    boolean courseExists(@Param("courseId") Long courseId);

    @Select("SELECT id FROM course WHERE code = #{courseCode} AND archived_at IS NULL LIMIT 1")
    Long courseIdByCode(@Param("courseCode") String courseCode);

    /** Restore-only existence lookup that deliberately includes archived rows. */
    @Select("SELECT CASE WHEN COUNT(*) > 0 THEN 1 ELSE 0 END FROM course WHERE id = #{courseId}")
    boolean courseExistsIncludingArchived(@Param("courseId") Long courseId);

    /**
     * 课程是否归属于指定教师（teacher_id 匹配）。
     * 课程不存在或 teacher_id 为 NULL 时返回 false。
     */
    @Select("""
            SELECT CASE WHEN COUNT(*) > 0 THEN 1 ELSE 0 END
            FROM course
            WHERE id = #{courseId}
              AND teacher_id = #{teacherId}
              AND archived_at IS NULL
            """)
    boolean isOwnedCourse(@Param("courseId") Long courseId, @Param("teacherId") Long teacherId);

    /** Restore-only ownership lookup that deliberately includes archived rows. */
    @Select("""
            SELECT CASE WHEN COUNT(*) > 0 THEN 1 ELSE 0 END
            FROM course
            WHERE id = #{courseId} AND teacher_id = #{teacherId}
            """)
    boolean isOwnedCourseIncludingArchived(@Param("courseId") Long courseId,
                                           @Param("teacherId") Long teacherId);

    @Select("""
            SELECT n.course_id
            FROM kg_node n
            JOIN course c ON c.id = n.course_id
            WHERE n.id = #{nodeId} AND c.archived_at IS NULL
            LIMIT 1
            """)
    Long courseIdForNode(@Param("nodeId") Long nodeId);

    @Select("""
            SELECT e.course_id
            FROM kg_edge e
            JOIN course c ON c.id = e.course_id
            WHERE e.id = #{edgeId} AND c.archived_at IS NULL
            LIMIT 1
            """)
    Long courseIdForEdge(@Param("edgeId") Long edgeId);

    @Select("""
            SELECT q.course_id
            FROM question q
            JOIN course c ON c.id = q.course_id
            WHERE q.id = #{questionId} AND c.archived_at IS NULL
            LIMIT 1
            """)
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
