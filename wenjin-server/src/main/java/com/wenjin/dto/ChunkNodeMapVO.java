package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 教师端 chunk↔节点映射审核项（含正文预览）。
 */
@Data
public class ChunkNodeMapVO {

    private Long id;
    private Long chunkId;
    private Long courseId;
    private String nodeCode;
    private BigDecimal confidence;
    /** 0=PENDING, 1=APPROVED, 2=REJECTED */
    private Integer reviewStatus;
    /** 切块正文预览（截断） */
    private String chunkPreview;
    private String sourceTitle;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reviewedAt;
}
