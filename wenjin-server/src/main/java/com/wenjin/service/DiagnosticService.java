package com.wenjin.service;

import com.wenjin.dto.PaperVO;
import com.wenjin.dto.SubmitRequest;
import com.wenjin.dto.SubmitResult;

/**
 * 入口诊断服务（T6：组卷；T7：交卷判分）。
 */
public interface DiagnosticService {

    /**
     * 按课程组装诊断试卷（分层抽样，答案不下行）。
     *
     * @param courseId 课程 ID
     * @return 诊断试卷视图（题目列表已剥离答案信息）
     */
    PaperVO composePaper(Long courseId);

    /**
     * 学生交卷判分：对每道题与题库正确答案比对，逐题写入 answer_record，
     * 返回汇总得分及逐题正误明细。本阶段不更新 student_mastery。
     *
     * @param req 学生提交的作答请求（studentId / courseId / answers）
     * @return 判分结果（total / correctCount / grades 列表）
     */
    SubmitResult submit(SubmitRequest req);
}
