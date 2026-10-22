package com.wenjin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenjin.entity.CompanionHintLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 主动陪伴提示日志 Mapper。
 */
@Mapper
public interface CompanionHintLogMapper extends BaseMapper<CompanionHintLog> {

    /** 记为已展示：同键只一行，shown_at 只记首次展示（重复调用为空操作，不把冷却往后推）。 */
    @Insert("""
            INSERT INTO companion_hint_log (student_id, course_id, hint_key, hint_type, shown_at)
            VALUES (#{studentId}, #{courseId}, #{hintKey}, #{hintType}, #{now})
            ON DUPLICATE KEY UPDATE shown_at = shown_at
            """)
    int upsertShown(@Param("studentId") Long studentId,
                    @Param("courseId") Long courseId,
                    @Param("hintKey") String hintKey,
                    @Param("hintType") String hintType,
                    @Param("now") LocalDateTime now);

    /** 记为已关闭：行不存在则补建（shown_at=dismissed_at=now）；已存在只更新 dismissed_at。 */
    @Insert("""
            INSERT INTO companion_hint_log (student_id, course_id, hint_key, hint_type, shown_at, dismissed_at)
            VALUES (#{studentId}, #{courseId}, #{hintKey}, #{hintType}, #{now}, #{now})
            ON DUPLICATE KEY UPDATE dismissed_at = VALUES(dismissed_at)
            """)
    int upsertDismissed(@Param("studentId") Long studentId,
                        @Param("courseId") Long courseId,
                        @Param("hintKey") String hintKey,
                        @Param("hintType") String hintType,
                        @Param("now") LocalDateTime now);
}
