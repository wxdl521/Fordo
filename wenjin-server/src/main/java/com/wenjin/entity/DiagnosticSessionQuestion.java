package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 固定卷诊断会话冻结题目（diagnostic_session_question）。
 */
@Data
@TableName("diagnostic_session_question")
public class DiagnosticSessionQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话（逻辑外键→diagnostic_session.id） */
    private Long sessionId;

    /** 题目（逻辑外键→question.id） */
    private Long questionId;

    /** 课程（与 question.course_id 一致，支撑复合 FK） */
    private Long courseId;

    /** 卷内顺序（0-based） */
    private Integer ordinal;
}
