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
    /** 无 PENDING/APPROVED 映射的块数（不可检索，需老师手动关联）。 */
    private long unlinkedChunkCount;
    /** 切块总数（= chunkCount，R2 覆盖率分母）。 */
    private long totalChunks;
    /** 当前嵌入模型下已有向量的块数；向量模式关时为 null（R2）。 */
    private Long embeddedChunks;
    /** 当前嵌入模型 modelId；向量模式关时为 null（R2）。 */
    private String embeddingModel;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
