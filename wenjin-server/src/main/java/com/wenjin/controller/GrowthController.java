package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.config.AccessGuard;
import com.wenjin.service.CourseService;
import com.wenjin.service.GrowthService;
import com.wenjin.vo.GrowthVO;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/growth")
@Validated
public class GrowthController {

    private final GrowthService growthService;
    private final CourseService courseService;

    public GrowthController(GrowthService growthService, CourseService courseService) {
        this.growthService = growthService;
        this.courseService = courseService;
    }

    /**
     * GET /api/growth?courseId=  （可选兼容参数 studentId）
     */
    @GetMapping
    public Result<GrowthVO> getGrowth(
            @RequestParam(value = "studentId", required = false) Long studentId,
            @RequestParam @Positive Long courseId) {
        long id = AccessGuard.requireSelfStudent(studentId);
        courseService.assertAccessibleByStudent(id, courseId);
        GrowthVO growth = growthService.getGrowth(id, courseId);
        return Result.ok(growth);
    }
}
