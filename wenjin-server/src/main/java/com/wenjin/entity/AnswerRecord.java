package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 答题记录实体（answer_record 表，每次作答留痕，掌握度计算原始来源）。
 */
@Data
@TableName("answer_record")
public class AnswerRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 学生（逻辑外键→sys_user.id） */
    private Long studentId;

    /** 课程（逻辑外键→course.id） */
    private Long courseId;

    /** 题目（逻辑外键→question.id） */
    private Long questionId;

    /** 学生作答内容 */
    private String studentAnswer;

    /** 是否正确：1=对, 0=错（简答可由AI判定） */
    private Integer isCorrect;

    private LocalDateTime answeredAt;

    /** 作答场景：1=诊断, 2=节点练习（M1补充） */
    private Integer scene;

    /** 练习会话ID（scene=2 时非空，逻辑外键→practice_session.id；M1补充） */
    private Long sessionId;
}
