package com.wenjin.service;

import com.wenjin.dto.PaperVO;

/**
 * 入口诊断服务（T6：组卷）。
 * 后续 T7 将在此接口追加提交答题方法。
 */
public interface DiagnosticService {

    /**
     * 按课程组装诊断试卷（分层抽样，答案不下行）。
     *
     * @param courseId 课程 ID
     * @return 诊断试卷视图（题目列表已剥离答案信息）
     */
    PaperVO composePaper(Long courseId);
}
