package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主动陪伴提示条展示/关闭日志（companion_hint_log 表，V22）。
 * <p>同一 (student, course, hintKey) 只记一行：再次展示更新 shownAt；关闭写 dismissedAt。</p>
 */
@Data
@TableName("companion_hint_log")
public class CompanionHintLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long studentId;

    private Long courseId;

    /** 提示实例键，如 ERROR_BURST:session:123 */
    private String hintKey;

    /** ERROR_BURST / DECAY / PLAN_BEHIND */
    private String hintType;

    private LocalDateTime shownAt;

    private LocalDateTime dismissedAt;
}
