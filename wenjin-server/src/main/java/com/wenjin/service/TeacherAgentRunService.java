package com.wenjin.service;

import com.wenjin.dto.AgentRunPageVO;
import com.wenjin.dto.AgentStepVO;

import java.util.List;

/**
 * 教师端 Agent 执行轨迹查询（归属校验 + 特性开关）。
 */
public interface TeacherAgentRunService {

    /**
     * 分页列出课程下的 agent runs。
     *
     * @param courseId  必填，先 {@code assertManageCourse}
     * @param studentId 可选过滤
     * @param page      页码（从 1 起；&lt;1 夹到 1）
     * @param size      每页条数（夹到 1..50）
     */
    AgentRunPageVO listRuns(Long courseId, Long studentId, int page, int size);

    /**
     * 列出某 run 的 steps（按 seq 升序）。
     * <p>run 不存在或不属于当前教师可管理课程 → 404。
     */
    List<AgentStepVO> listSteps(Long runId);
}
