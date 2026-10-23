package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 每 学生×课程×节点 最近一次探针结果与换策略状态（probe_outcome 表，V26）。 */
@Data
@TableName("probe_outcome")
public class ProbeOutcomeRow {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long studentId;
    private Long courseId;
    private String nodeCode;
    private Boolean correct;
    private String strategy;
    private String activeStrategy;
    /** 逗号分隔的 Strategy 名 */
    private String triedStrategies;
    private Boolean switchConsumed;
    private Boolean outcomeRecorded;
    private LocalDateTime updatedAt;
}
