package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 理解探针会话（probe_session 表，V26）。 */
@Data
@TableName("probe_session")
public class ProbeSessionRow {

    @TableId(type = IdType.INPUT)
    private String sessionId;
    private Long studentId;
    private Long courseId;
    private String nodeCode;
    private Long nodeId;
    private String stem;
    private Integer type;
    private String optionsJson;
    private String correctKey;
    /** BANK / EPHEMERAL */
    private String source;
    private Long bankQuestionId;
    private LocalDateTime createdAt;
    private LocalDateTime submittedAt;
    private Boolean correct;
}
