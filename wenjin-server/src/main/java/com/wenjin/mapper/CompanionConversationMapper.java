package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.CompanionConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface CompanionConversationMapper extends BaseMapper<CompanionConversation> {

    /**
     * 摘要字段条件回写：仅当 throughId 严格前进（或当前 watermark 为空）时更新，
     * 避免与会话切换的整实体 updateById 并发时 lost-update 覆盖 node_code。
     *
     * @return 影响行数；0 表示 watermark 已被并发者推进，调用方应静默放弃本次回写
     */
    @Update("UPDATE companion_conversation SET summary_text = #{summaryText}, " +
            "summary_through_message_id = #{throughId}, summary_origin = 0, updated_at = NOW() " +
            "WHERE id = #{id} AND (summary_through_message_id IS NULL " +
            "OR summary_through_message_id < #{throughId})")
    int updateSummaryGuarded(@Param("id") Long id,
                             @Param("summaryText") String summaryText,
                             @Param("throughId") Long throughId);

    /**
     * SUMMARY_REFRESH 等式守卫：仅当 watermark 仍等于 expectedThrough 时覆盖摘要，
     * 防止请求路径已把 through 推得更远后被旧 LLM 结果回写。
     *
     * @return 影响行数；0 表示 through 已变，静默放弃
     */
    @Update("UPDATE companion_conversation SET summary_text = #{summaryText}, " +
            "summary_origin = 1, updated_at = NOW() " +
            "WHERE id = #{id} AND summary_through_message_id = #{expectedThrough}")
    int updateSummaryRefreshGuarded(@Param("id") Long id,
                                    @Param("summaryText") String summaryText,
                                    @Param("expectedThrough") Long expectedThrough);

    /**
     * MEMORY_DISTILL 蒸馏 watermark 前进守卫（同 P0-1 语义：仅严格前进）。
     *
     * @return 影响行数；0 表示已被并发者推进
     */
    @Update("UPDATE companion_conversation SET distilled_through_message_id = #{throughId}, " +
            "updated_at = NOW() " +
            "WHERE id = #{id} AND (distilled_through_message_id IS NULL " +
            "OR distilled_through_message_id < #{throughId})")
    int updateDistilledThroughGuarded(@Param("id") Long id,
                                      @Param("throughId") Long throughId);

    /**
     * Keyset 分页：按 updated_at DESC, id DESC。
     * beforeId 非空时，仅返回严格早于该会话（updated_at,id）的记录。
     */
    @Select("""
            <script>
            SELECT c.id, c.student_id, c.course_id, c.title, c.node_code,
                   c.summary_text, c.summary_origin, c.summary_through_message_id,
                   c.distilled_through_message_id, c.created_at, c.updated_at
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
