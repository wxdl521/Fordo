package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * chunk↔节点映射（chunk_node_map，M8）。
 * <p>review_status: 0=PENDING,1=APPROVED,2=REJECTED。
 */
@Data
@TableName("chunk_node_map")
public class ChunkNodeMap {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long chunkId;

    private Long courseId;

    private String nodeCode;

    private BigDecimal confidence;

    /** 0=PENDING, 1=APPROVED, 2=REJECTED */
    private Integer reviewStatus;

    private Long reviewedBy;

    private LocalDateTime reviewedAt;
}
