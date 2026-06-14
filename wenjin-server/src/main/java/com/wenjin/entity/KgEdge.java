package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识点关系实体（kg_edge 表，图谱边）。
 */
@Data
@TableName("kg_edge")
public class KgEdge {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long courseId;

    /** 起点知识点主键（kg_node.id） */
    private Long fromNodeId;

    /** 终点知识点主键（kg_node.id） */
    private Long toNodeId;

    /** 关系类型：1=前置, 2=包含, 3=相关, 4=应用 */
    private Integer relationType;

    /** 关系备注 */
    private String relationNote;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
