package com.wenjin.service;

import com.wenjin.dto.PaperVO;
import com.wenjin.dto.SubmitRequest;
import com.wenjin.dto.SubmitResult;

/**
 * 入口诊断服务（T6：组卷冻结；T7：精确集合交卷 + 幂等结果）。
 */
public interface DiagnosticService {

    /**
     * 按课程组装诊断试卷（分层抽样，答案不下行），并冻结会话题集。
     * 要求当前学生已认证且可访问该已发布课程。
     *
     * @param courseId 课程 ID
     * @return 诊断试卷视图（含 sessionToken；题目列表已剥离答案信息）
     */
    PaperVO composePaper(Long courseId);

    /**
     * 学生交卷判分：按 sessionToken 锁定会话，校验归属与题集精确匹配后判分，
     * 写入 answer_record 与掌握度一次；已完成会话幂等返回 result_json。
     *
     * @param req 须含 sessionToken + answers（questionId 集 = 冻结集）；studentId 由 Controller 写入
     * @return 判分结果（total / correctCount / grades 列表）
     */
    SubmitResult submit(SubmitRequest req);
}
