package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.StudentMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 学生长期记忆 Mapper。
 */
@Mapper
public interface StudentMemoryMapper extends BaseMapper<StudentMemory> {

    /**
     * 活跃记忆列表（同学生×课程；可选 kind 过滤）。
     * 按 last_reinforced_at DESC, id DESC 排序，供 effective 排序/注入与 Jaccard 去重扫描。
     */
    @Select("""
            <script>
            SELECT id, student_id, course_id, kind, content, node_code, source_run_id,
                   salience, last_reinforced_at, status, created_at
            FROM student_memory
            WHERE student_id = #{studentId}
              AND course_id = #{courseId}
              AND status = 1
            <if test="kind != null">
              AND kind = #{kind}
            </if>
            ORDER BY last_reinforced_at DESC, id DESC
            </script>
            """)
    List<StudentMemory> selectActive(@Param("studentId") Long studentId,
                                     @Param("courseId") Long courseId,
                                     @Param("kind") Integer kind);

    /**
     * content LIKE 检索（query 已在服务层转义 %/_；仅本人×课程×ACTIVE）。
     */
    @Select("""
            <script>
            SELECT id, student_id, course_id, kind, content, node_code, source_run_id,
                   salience, last_reinforced_at, status, created_at
            FROM student_memory
            WHERE student_id = #{studentId}
              AND course_id = #{courseId}
              AND status = 1
              AND content LIKE CONCAT('%', #{query}, '%')
            <if test="kind != null">
              AND kind = #{kind}
            </if>
            ORDER BY last_reinforced_at DESC, id DESC
            </script>
            """)
    List<StudentMemory> selectByContentLike(@Param("studentId") Long studentId,
                                            @Param("courseId") Long courseId,
                                            @Param("query") String query,
                                            @Param("kind") Integer kind);

    /**
     * 命中/注入强化：仅刷新 last_reinforced_at（不改 salience / content）。
     *
     * @return 影响行数
     */
    @Update("UPDATE student_memory SET last_reinforced_at = #{reinforcedAt} " +
            "WHERE id = #{id} AND status = 1")
    int reinforce(@Param("id") Long id, @Param("reinforcedAt") LocalDateTime reinforcedAt);
}
