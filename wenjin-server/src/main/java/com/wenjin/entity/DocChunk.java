package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 语料切块（doc_chunk，M8）。
 */
@Data
@TableName("doc_chunk")
public class DocChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sourceId;

    /** 同 source 内从 0 递增 */
    private Integer seq;

    private String content;

    private Integer charCount;

    /** 标题路径/页码等 JSON（可空） */
    private String metaJson;
}
