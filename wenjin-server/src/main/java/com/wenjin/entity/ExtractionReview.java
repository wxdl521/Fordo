package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 课程标准抽取审核指标记录(extraction_review,只增不改)。 */
@Data
@TableName("extraction_review")
public class ExtractionReview {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String courseCode;
    private Long courseId;

    private Integer nodeAiCount;
    private Integer nodeKeptCount;
    private Integer nodeDeletedCount;
    private Integer nodeAddedCount;
    private Integer nodeModifiedCount;
    private Integer nodeFinalCount;
    private BigDecimal nodeRecall;
    private BigDecimal nodePrecision;

    private Integer edgeAiCount;
    private Integer edgeKeptCount;
    private Integer edgeDeletedCount;
    private Integer edgeAddedCount;
    private Integer edgeModifiedCount;
    private Integer edgeFinalCount;
    private BigDecimal edgeRecall;
    private BigDecimal edgePrecision;

    private LocalDateTime createdAt;
}
