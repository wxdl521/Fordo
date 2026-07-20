package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.AgentReportVO;
import com.wenjin.service.TeacherReportService;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 教师端多智能体报告查询。
 * <p>特性开关 {@code wenjin.features.multi-agent-enabled=false} 时统一 404。
 */
@RestController
@RequestMapping("/api/teacher/reports")
@Validated
public class TeacherReportController {

    private final TeacherReportService teacherReportService;

    public TeacherReportController(TeacherReportService teacherReportService) {
        this.teacherReportService = teacherReportService;
    }

    /**
     * GET /api/teacher/reports?courseId=&amp;kind=
     */
    @GetMapping
    public Result<List<AgentReportVO>> list(
            @RequestParam @Positive Long courseId,
            @RequestParam(required = false) Integer kind
    ) {
        return Result.ok(teacherReportService.list(courseId, kind));
    }

    /**
     * GET /api/teacher/reports/{id}
     */
    @GetMapping("/{id}")
    public Result<AgentReportVO> get(@PathVariable("id") @Positive Long id) {
        return Result.ok(teacherReportService.getById(id));
    }
}
