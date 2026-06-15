package com.wenjin.dto;

import lombok.Data;

/**
 * 学习路径生成请求。targetNodeId 缺省（null）时服务端取诊断卡点。
 */
@Data
public class PathGenerateRequest {

    private Long studentId;
    private Long courseId;

    /** 目标节点 id（可空：缺省=诊断卡点） */
    private Long targetNodeId;

    /** 是否调 AI 生成每步说明（默认 false=模板） */
    private boolean useAi;
}
