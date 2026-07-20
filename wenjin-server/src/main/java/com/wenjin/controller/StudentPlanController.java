package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.config.AccessGuard;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.PlanVO;
import com.wenjin.service.CourseService;
import com.wenjin.service.plan.PlanService;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生计划端点（M10）：confirm / cancel / current。身份一律从 auth 派生。
 */
@RestController
@RequestMapping("/api/student/plans")
@Validated
public class StudentPlanController {

    private final PlanService planService;
    private final CourseService courseService;
    private final FeatureFlagsProperties featureFlags;

    public StudentPlanController(PlanService planService,
                                 CourseService courseService,
                                 FeatureFlagsProperties featureFlags) {
        this.planService = planService;
        this.courseService = courseService;
        this.featureFlags = featureFlags;
    }

    @PostMapping("/{id}/confirm")
    public Result<PlanVO> confirm(@PathVariable("id") @Positive Long id) {
        requireEnabled();
        long studentId = AccessGuard.requireSelfStudent(null);
        return Result.ok(planService.confirm(studentId, id));
    }

    @PostMapping("/{id}/cancel")
    public Result<PlanVO> cancel(@PathVariable("id") @Positive Long id) {
        requireEnabled();
        long studentId = AccessGuard.requireSelfStudent(null);
        return Result.ok(planService.cancel(studentId, id));
    }

    @GetMapping("/current")
    public Result<PlanVO> current(@RequestParam("courseId") @Positive Long courseId) {
        requireEnabled();
        long studentId = AccessGuard.requireSelfStudent(null);
        courseService.assertAccessibleByStudent(studentId, courseId);
        return Result.ok(planService.current(studentId, courseId));
    }

    private void requireEnabled() {
        if (featureFlags != null && !featureFlags.isPlanEnabled()) {
            throw new com.wenjin.common.BusinessException(
                    com.wenjin.common.ResultCode.NOT_FOUND, "计划功能未开启");
        }
    }
}
