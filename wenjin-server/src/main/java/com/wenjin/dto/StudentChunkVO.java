package com.wenjin.dto;

import lombok.Data;

/**
 * 学生端语料切块详情（引用抽屉）。
 */
@Data
public class StudentChunkVO {

    private Long id;
    private String content;
    private String sourceTitle;
    private String metaJson;
}
