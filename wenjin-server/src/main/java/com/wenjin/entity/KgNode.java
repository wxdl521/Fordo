package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识点实体（kg_node 表，图谱节点）。
 * 主键 id 自增，与业务编码 nodeCode（如 KT12）分离。
 */
@Data
@TableName("kg_node")
public class KgNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属课程（多课程隔离） */
    private Long courseId;

    /** 业务ID（如 KT12），来源于导入 JSON 的 node.id */
    private String nodeCode;

    private String name;

    private String chapter;

    /** 难度 1–5 */
    private Integer difficulty;

    /** 是否重点：1=是, 0=否 */
    private Integer isKey;

    /** 布卢姆认知层级（理解/运用/分析…） */
    private String bloom;

    private String description;

    /** 备注（图谱评审说明等） */
    private String nodeNote;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
