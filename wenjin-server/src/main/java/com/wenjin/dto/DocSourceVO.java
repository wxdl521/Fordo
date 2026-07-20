package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 教师端语料来源列表项（M8 UI）。
 */
@Data
public class DocSourceVO {

    private Long id;
    private Long courseId;
    private String title;
    /** 0=COURSEWARE, 1=TEXTBOOK, 2=NODE_REF */
    private Integer kind;
    /** 0=INGESTING, 1=READY, 2=FAILED, 3=ARCHIVED */
    private Integer status;
    private long chunkCount;
    private long pendingMapCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
