package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.TeacherConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface TeacherConversationMapper extends BaseMapper<TeacherConversation> {

    /**
     * 摘要条件回写：仅当 throughId 严格前进（或当前 watermark 为空）时更新。
     *
     * @return 影响行数；0 表示 watermark 已被并发者推进，调用方静默放弃
     */
    @Update("UPDATE teacher_conversation SET summary_text = #{summaryText}, " +
            "summary_through_message_id = #{throughId}, updated_at = NOW() " +
            "WHERE id = #{id} AND (summary_through_message_id IS NULL " +
            "OR summary_through_message_id < #{throughId})")
    int updateSummaryGuarded(@Param("id") Long id,
                             @Param("summaryText") String summaryText,
                             @Param("throughId") Long throughId);

    /** Keyset 分页：本人 × 课程，按 updated_at DESC, id DESC。 */
    @Select("""
            <script>
            SELECT c.id, c.teacher_id, c.course_id, c.title, c.summary_text,
                   c.summary_through_message_id, c.created_at, c.updated_at
            FROM teacher_conversation c
            WHERE c.teacher_id = #{teacherId}
              AND c.course_id = #{courseId}
            <if test="beforeId != null">
              AND (
                c.updated_at &lt; (SELECT b.updated_at FROM teacher_conversation b WHERE b.id = #{beforeId})
                OR (
                  c.updated_at = (SELECT b.updated_at FROM teacher_conversation b WHERE b.id = #{beforeId})
                  AND c.id &lt; #{beforeId}
                )
              )
            </if>
            ORDER BY c.updated_at DESC, c.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<TeacherConversation> selectKeysetByTeacherCourse(@Param("teacherId") Long teacherId,
                                                          @Param("courseId") Long courseId,
                                                          @Param("beforeId") Long beforeId,
                                                          @Param("limit") int limit);
}
