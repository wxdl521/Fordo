package com.wenjin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 学习路径生成请求。targetNodeId 缺省（null）时服务端取诊断卡点。
 */
@Data
public class PathGenerateRequest {

    private Long studentId;

    @NotNull(message = "courseId 不能为空")
    @Positive(message = "courseId 必须为正数")
    private Long courseId;

    /** 目标节点 id（可空：缺省=诊断卡点） */
    @Positive(message = "targetNodeId 必须为正数")
    private Long targetNodeId;

    /** 是否调 AI 生成每步说明（默认 false=模板） */
    private boolean useAi;
}
