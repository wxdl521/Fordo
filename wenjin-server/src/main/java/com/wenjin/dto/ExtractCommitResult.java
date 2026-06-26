package com.wenjin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 提交结果:导入摘要 + 本次审核指标。 */
@Data
@AllArgsConstructor
public class ExtractCommitResult {
    private GraphImportResult importResult;
    private ExtractionMetrics metrics;
}
