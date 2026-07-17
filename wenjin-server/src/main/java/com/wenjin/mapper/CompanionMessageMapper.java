package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.CompanionMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CompanionMessageMapper extends BaseMapper<CompanionMessage> {

    /**
     * Keyset 分页：取 id &lt; beforeId（beforeId 为空则不限）的最新 limit 条，按 id 降序。
     * 调用方通常再反转为时间正序展示。
     */
    @Select("""
            <script>
            SELECT id, conversation_id, role, content, created_at
            FROM companion_message
            WHERE conversation_id = #{conversationId}
            <if test="beforeId != null">
              AND id &lt; #{beforeId}
            </if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<CompanionMessage> selectKeysetByConversation(@Param("conversationId") Long conversationId,
                                                      @Param("beforeId") Long beforeId,
                                                      @Param("limit") int limit);

    /** 会话全量历史（升序），供 AI 上下文构建。 */
    @Select("""
            SELECT id, conversation_id, role, content, created_at
            FROM companion_message
            WHERE conversation_id = #{conversationId}
            ORDER BY id ASC
            """)
    List<CompanionMessage> selectAllByConversationAsc(@Param("conversationId") Long conversationId);

    /**
     * 摘要 watermark 之后的历史（升序）。
     * {@code afterId} 为空时等价全量；非空时仅 {@code id > afterId}（已进摘要的消息不再拉取）。
     */
    @Select("""
            <script>
            SELECT id, conversation_id, role, content, created_at
            FROM companion_message
            WHERE conversation_id = #{conversationId}
            <if test="afterId != null">
              AND id &gt; #{afterId}
            </if>
            ORDER BY id ASC
            </script>
            """)
    List<CompanionMessage> selectSinceIdAsc(@Param("conversationId") Long conversationId,
                                            @Param("afterId") Long afterId);
}
