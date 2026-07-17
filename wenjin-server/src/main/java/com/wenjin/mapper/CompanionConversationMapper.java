package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.CompanionConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CompanionConversationMapper extends BaseMapper<CompanionConversation> {

    /**
     * Keyset 分页：按 updated_at DESC, id DESC。
     * beforeId 非空时，仅返回严格早于该会话（updated_at,id）的记录。
     */
    @Select("""
            <script>
            SELECT c.id, c.student_id, c.course_id, c.title, c.node_code,
                   c.summary_text, c.summary_through_message_id, c.created_at, c.updated_at
            FROM companion_conversation c
            WHERE c.student_id = #{studentId}
              AND c.course_id = #{courseId}
            <if test="beforeId != null">
              AND (
                c.updated_at &lt; (SELECT b.updated_at FROM companion_conversation b WHERE b.id = #{beforeId})
                OR (
                  c.updated_at = (SELECT b.updated_at FROM companion_conversation b WHERE b.id = #{beforeId})
                  AND c.id &lt; #{beforeId}
                )
              )
            </if>
            ORDER BY c.updated_at DESC, c.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<CompanionConversation> selectKeysetByStudentCourse(@Param("studentId") Long studentId,
                                                            @Param("courseId") Long courseId,
                                                            @Param("beforeId") Long beforeId,
                                                            @Param("limit") int limit);
}
