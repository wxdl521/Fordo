package com.wenjin.service;

import com.wenjin.dto.DiagnosticResultVO;

/**
 * 诊断回溯服务：对薄弱点沿"前置"边逆向回溯根因（PRD 6.2）。
 */
public interface DiagnosticResultService {

    /**
     * 计算某学生在某课程的诊断回溯结果（自动选最下游薄弱点为卡点）。
     */
    DiagnosticResultVO getResult(Long studentId, Long courseId);
}
