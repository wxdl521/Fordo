package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.TeacherMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TeacherMessageMapper extends BaseMapper<TeacherMessage> {

    /** Keyset 分页：id &lt; beforeId 的最新 limit 条，按 id 降序（调用方反转为正序）。 */
    @Select("""
            <script>
            SELECT id, conversation_id, role, content, created_at
            FROM teacher_message
            WHERE conversation_id = #{conversationId}
            <if test="beforeId != null">
              AND id &lt; #{beforeId}
            </if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<TeacherMessage> selectKeysetByConversation(@Param("conversationId") Long conversationId,
                                                    @Param("beforeId") Long beforeId,
                                                    @Param("limit") int limit);

    /** 摘要 watermark 之后的历史（升序）；afterId 为空时等价全量。 */
    @Select("""
            <script>
            SELECT id, conversation_id, role, content, created_at
            FROM teacher_message
            WHERE conversation_id = #{conversationId}
            <if test="afterId != null">
              AND id &gt; #{afterId}
            </if>
            ORDER BY id ASC
            </script>
            """)
    List<TeacherMessage> selectSinceIdAsc(@Param("conversationId") Long conversationId,
                                          @Param("afterId") Long afterId);

    @Delete("DELETE FROM teacher_message WHERE conversation_id = #{conversationId}")
    int deleteByConversation(@Param("conversationId") Long conversationId);
}
