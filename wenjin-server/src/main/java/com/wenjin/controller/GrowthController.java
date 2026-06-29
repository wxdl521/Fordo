package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.service.CourseService;
import com.wenjin.service.GrowthService;
import com.wenjin.vo.GrowthVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/growth")
public class GrowthController {

    private final GrowthService growthService;
    private final CourseService courseService;

    public GrowthController(GrowthService growthService, CourseService courseService) {
        this.growthService = growthService;
        this.courseService = courseService;
    }

    @GetMapping
    public Result<GrowthVO> getGrowth(
            @RequestParam Long studentId,
            @RequestParam Long courseId) {
        courseService.assertAccessibleByStudent(studentId, courseId);
        GrowthVO growth = growthService.getGrowth(studentId, courseId);
        return Result.ok(growth);
    }
}
