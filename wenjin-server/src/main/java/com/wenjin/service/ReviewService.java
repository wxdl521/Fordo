package com.wenjin.service;

import com.wenjin.dto.ReviewTodayVO;

/**
 * 复习队列服务（M3）：按读时衰减筛选「掌握过、正在遗忘」的节点并排序截断。
 */
public interface ReviewService {

    /**
     * 今日复习队列：urgency 降序取 top N，全量计数在 totalDecayingCount。
     */
    ReviewTodayVO getToday(Long studentId, Long courseId);
}