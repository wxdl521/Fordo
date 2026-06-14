package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 题目-知识点关联实体（question_node 表，含主次权重，认知诊断基础）。
 */
@Data
@TableName("question_node")
public class QuestionNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 题目（逻辑外键→question.id） */
    private Long questionId;

    /** 关联知识点（逻辑外键→kg_node.id） */
    private Long nodeId;

    /** 主次权重：1=主, 2=次 */
    private Integer weight;
}
