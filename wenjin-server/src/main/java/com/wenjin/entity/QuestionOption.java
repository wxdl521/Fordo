package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 题目选项实体（question_option 表，仅客观题使用）。
 */
@Data
@TableName("question_option")
public class QuestionOption {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属题目（逻辑外键→question.id） */
    private Long questionId;

    /** 选项标识（A/B/C/D） */
    private String optionKey;

    /** 选项内容 */
    private String optionText;

    /** 是否正确：1=是, 0=否 */
    private Integer isCorrect;

    /** 该错误选项暴露的薄弱知识点 node_code（distractor_map，正确项为空）【阶段二补充】 */
    private String pointNodeCode;
}
