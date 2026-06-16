package com.wenjin.service;

import com.wenjin.vo.GrowthVO;

/**
 * 成长档案服务：聚合快照数据生成成长曲线和前后对比。
 */
public interface GrowthService {

    /**
     * 获取学生在某课程下的成长档案：成长曲线 + 前后对比。
     *
     * @param studentId 学生 ID
     * @param courseId  课程 ID
     * @return 成长档案 VO
     */
    GrowthVO getGrowth(Long studentId, Long courseId);
}
