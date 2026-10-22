package com.wenjin.dto;

import lombok.Data;

/** 教师端文件上传入库的受理结果：来源 + 入库作业。 */
@Data
public class DocUploadVO {

    private Long sourceId;
    private Long jobId;
    private String title;
    /** 0=COURSEWARE, 1=TEXTBOOK */
    private Integer kind;
    /** 受理时恒为 0=INGESTING */
    private Integer status;
}
