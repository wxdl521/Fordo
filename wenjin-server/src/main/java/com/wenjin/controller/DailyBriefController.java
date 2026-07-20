package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.config.AccessGuard;
import com.wenjin.dto.DailyBriefVO;
import com.wenjin.service.CourseService;
import com.wenjin.service.plan.DailyBriefService;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 每日简报（M10）：读时聚合，无推送。
 */
@RestController
@RequestMapping("/api/student")
@Validated
public class DailyBriefController {

    private final DailyBriefService dailyBriefService;
    private final CourseService courseService;

    public DailyBriefController(DailyBriefService dailyBriefService, CourseService courseService) {
        this.dailyBriefService = dailyBriefService;
        this.courseService = courseService;
    }

    @GetMapping("/daily-brief")
    public Result<DailyBriefVO> dailyBrief(@RequestParam("courseId") @Positive Long courseId) {
        long studentId = AccessGuard.requireSelfStudent(null);
        courseService.assertAccessibleByStudent(studentId, courseId);
        return Result.ok(dailyBriefService.build(studentId, courseId));
    }
}
