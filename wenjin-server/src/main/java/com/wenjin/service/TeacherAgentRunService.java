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
     * 同上，可选按 kind 过滤（方向 7）。
     *
     * @param kind 为空 = 除 {@code TEACHER_CHAT} 外的全部；{@code TEACHER_CHAT} 时只返回当前用户自己的助教轨迹
     *             （该 kind 的 student_id 列存老师 id，此时忽略 studentId 参数）
     */
    default AgentRunPageVO listRuns(Long courseId, Long studentId, String kind, int page, int size) {
        return listRuns(courseId, studentId, page, size);
    }

    /**
     * 列出某 run 的 steps（按 seq 升序）。
     * <p>run 不存在或不属于当前教师可管理课程 → 404。
     */
    List<AgentStepVO> listSteps(Long runId);
}
