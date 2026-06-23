package com.wenjin.service;

import com.wenjin.dto.GraphPreviewResult;

/** AI 图谱预览 SVG 服务。 */
public interface GraphPreviewService {

    /** 为课程图谱生成预览 SVG（含校验 + 修复重试）。 */
    GraphPreviewResult generatePreviewSvg(Long courseId);
}
