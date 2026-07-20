package com.wenjin.service;

import com.wenjin.dto.AgentReportVO;

import java.util.List;

/**
 * 教师端多智能体报告查询。
 */
public interface TeacherReportService {

    /**
     * 列表：courseId 必填；kind 可选过滤。
     */
    List<AgentReportVO> list(Long courseId, Integer kind);

    /**
     * 详情：须归属可管理课程。
     */
    AgentReportVO getById(Long id);
}
