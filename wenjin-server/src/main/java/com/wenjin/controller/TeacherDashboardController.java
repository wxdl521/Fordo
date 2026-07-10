package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.DashboardVO;
import com.wenjin.service.TeacherDashboardService;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 学情看板接口【阶段六，无权限，演示】。 */
@RestController
@RequestMapping("/api/teacher/dashboard")
@Validated
public class TeacherDashboardController {

    private final TeacherDashboardService service;

    public TeacherDashboardController(TeacherDashboardService service) {
        this.service = service;
    }

    @GetMapping
    public Result<DashboardVO> dashboard(@RequestParam @Positive Long courseId) {
        return Result.ok(service.dashboard(courseId));
    }
}
