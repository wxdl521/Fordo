package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 编程题测试用例（question_code_case 表，V24）。
 */
@Data
@TableName("question_code_case")
public class QuestionCodeCase {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属编程题（逻辑外键→question.id） */
    private Long questionId;

    /** 用例序号（从 1 起，题内唯一） */
    private Integer seq;

    /** 标准输入 */
    private String stdin;

    /** 期望标准输出 */
    private String expectedStdout;

    /** 1=隐藏用例（学生侧不可见输入/期望/实际） */
    private Integer hidden;

    private LocalDateTime createdAt;
}
