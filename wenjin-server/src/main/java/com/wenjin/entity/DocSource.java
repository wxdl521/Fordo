package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 语料来源（doc_source，M8）。
 * <p>kind: 0=COURSEWARE,1=TEXTBOOK,2=NODE_REF；status: 0=INGESTING,1=READY,2=FAILED,3=ARCHIVED。
 */
@Data
@TableName("doc_source")
public class DocSource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long courseId;

    private String title;

    /** 0=COURSEWARE, 1=TEXTBOOK, 2=NODE_REF */
    private Integer kind;

    /** 内容寻址 hash（可空） */
    private String fileHash;

    /** 0=INGESTING, 1=READY, 2=FAILED, 3=ARCHIVED */
    private Integer status;

    private Long createdBy;

    private LocalDateTime createdAt;
}
