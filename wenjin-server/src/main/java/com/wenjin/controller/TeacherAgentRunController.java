package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.AgentRunPageVO;
import com.wenjin.dto.AgentStepVO;
import com.wenjin.service.TeacherAgentRunService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 教师端 Agent 执行轨迹查询。
 * <p>特性开关 {@code wenjin.features.agent-trace-enabled=false} 时统一 404。
 * 学生端不开放 trace 接口。
 */
@RestController
@RequestMapping("/api/teacher/agent-runs")
@Validated
public class TeacherAgentRunController {

    private final TeacherAgentRunService teacherAgentRunService;

    public TeacherAgentRunController(TeacherAgentRunService teacherAgentRunService) {
        this.teacherAgentRunService = teacherAgentRunService;
    }

    /**
     * GET /api/teacher/agent-runs?courseId=&amp;studentId=&amp;kind=&amp;page=&amp;size=
     * <p>size 在服务层夹紧到 ≤50。kind 缺省时排除 TEACHER_CHAT（教师助教轨迹）；
     * kind=TEACHER_CHAT 只返回当前用户自己的助教轨迹。
     */
    @GetMapping
    public Result<AgentRunPageVO> list(
            @RequestParam @Positive Long courseId,
            @RequestParam(required = false) @Positive Long studentId,
            @RequestParam(required = false) @Size(max = 32) String kind,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return Result.ok(teacherAgentRunService.listRuns(courseId, studentId, kind, page, size));
    }

    /**
     * GET /api/teacher/agent-runs/{runId}/steps
     * <p>校验 run 属于当前教师可管理课程，否则 404。
     */
    @GetMapping("/{runId}/steps")
    public Result<List<AgentStepVO>> steps(@PathVariable("runId") @Positive Long runId) {
        return Result.ok(teacherAgentRunService.listSteps(runId));
    }
}
