package com.wenjin.controller;

import com.wenjin.common.BusinessException;
import com.wenjin.common.Result;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AccessGuard;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.CompanionHintDismissRequest;
import com.wenjin.dto.CompanionOpenerVO;
import com.wenjin.service.CourseService;
import com.wenjin.service.proactive.CompanionOpenerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 主动陪伴（方向 4）：伴侣 opener、提示条计数与关闭。零 LLM。
 * <p>studentId 一律取登录主体；功能开关关闭时先于课程访问校验返回 404（与其他受开关保护的端点一致）。</p>
 */
@RestController
@RequestMapping("/api/companion")
@Validated
public class CompanionOpenerController {

    private final CompanionOpenerService openerService;
    private final CourseService courseService;
    private final FeatureFlagsProperties featureFlags;

    public CompanionOpenerController(CompanionOpenerService openerService,
                                     CourseService courseService,
                                     FeatureFlagsProperties featureFlags) {
        this.openerService = openerService;
        this.courseService = courseService;
        this.featureFlags = featureFlags;
    }

    private void requireEnabled() {
        if (featureFlags != null && !featureFlags.isProactiveEnabled()) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
    }

    /** 空会话 opener：greeting + actions + hints（本次返回的 hints 记为已展示）。 */
    @GetMapping("/opener")
    public Result<CompanionOpenerVO> opener(@RequestParam("courseId") @Positive Long courseId) {
        requireEnabled();
        long studentId = AccessGuard.requireSelfStudent(null);
        courseService.assertAccessibleByStudent(studentId, courseId);
        return Result.ok(openerService.build(studentId, courseId));
    }

    /** 可见提示数（只读，不记 shown）；供导航小红点。 */
    @GetMapping("/hints/count")
    public Result<Map<String, Integer>> hintsCount(@RequestParam("courseId") @Positive Long courseId) {
        requireEnabled();
        long studentId = AccessGuard.requireSelfStudent(null);
        courseService.assertAccessibleByStudent(studentId, courseId);
        return Result.ok(Map.of("count", openerService.hintCount(studentId, courseId)));
    }

    /** 关闭一条提示（只能操作自己的）。 */
    @PostMapping("/hints/dismiss")
    public Result<Void> dismiss(@Valid @RequestBody CompanionHintDismissRequest req) {
        requireEnabled();
        long studentId = AccessGuard.requireSelfStudent(null);
        courseService.assertAccessibleByStudent(studentId, req.getCourseId());
        openerService.dismiss(studentId, req.getCourseId(), req.getHintKey());
        return Result.ok(null);
    }
}
