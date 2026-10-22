package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 语料切块向量（doc_chunk_embedding，V23）。一块一行，主键即 chunk_id；换模型/维度时覆盖。
 */
@Data
@TableName("doc_chunk_embedding")
public class DocChunkEmbedding {

    /** 逻辑外键 → doc_chunk.id（非自增） */
    @TableId(type = IdType.INPUT)
    private Long chunkId;

    /** 模型标识，如 embedding-3@1024 */
    private String model;

    private Integer dim;

    /** float32 little-endian */
    private byte[] vector;

    /** chunk 内容 SHA-256（hex），内容未变则可跳过重嵌 */
    private String contentSha;

    private LocalDateTime createdAt;
}
