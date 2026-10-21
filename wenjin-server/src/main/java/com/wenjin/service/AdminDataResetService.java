package com.wenjin.service;

import com.wenjin.dto.AdminDataResetVO;

/**
 * 管理后台「数据维护」：按课程清空学生学习数据（不删选课、图谱、题库、用户）。
 */
public interface AdminDataResetService {

    /**
     * 单事务清空该课程下全部学生的学习数据。
     *
     * @param confirmText 必须等于课程名
     * @throws com.wenjin.common.BusinessException 403 prod 未显式开启 / 404 课程不存在 / 400 确认文本不符
     */
    AdminDataResetVO resetCourse(Long courseId, String confirmText);
}
