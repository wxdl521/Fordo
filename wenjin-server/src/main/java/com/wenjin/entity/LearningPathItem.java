package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学习路径明细实体（learning_path_item 表，路径中的每一步）。
 */
@Data
@TableName("learning_path_item")
public class LearningPathItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属路径（逻辑外键→learning_path.id） */
    private Long pathId;

    /** 该步知识点（逻辑外键→kg_node.id） */
    private Long nodeId;

    /** 步骤顺序（拓扑排序结果，从 1 递增） */
    private Integer stepOrder;

    /** 完成状态：0=未学, 1=已完成 */
    private Integer status;

    private LocalDateTime completedAt;

    /** 「为什么学这个」说明（默认模板，可由 AI 生成） */
    private String reason;
}
