package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 题目实体（question 表，题干/题型/难度/答案）。
 */
@Data
@TableName("question")
public class Question {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属课程（逻辑外键→course.id） */
    private Long courseId;

    /** 题干 */
    private String stem;

    /** 题型：1=单选, 2=多选, 3=判断, 4=简答 */
    private Integer type;

    /** 难度 1–5 */
    private Integer difficulty;

    /** 标准答案（客观题存选项标识，简答存参考答案） */
    private String answer;

    /** 解析 */
    private String analysis;

    /** 来源：1=学校题库, 2=AI生成, 3=人工录入 */
    private Integer source;

    /** 审核状态：0=待审核, 1=已通过, 2=已驳回【阶段二补充】 */
    private Integer status;

    /** AI 置信度 0–100，NULL=既有生效题【阶段六补充】 */
    private Integer confidence;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
