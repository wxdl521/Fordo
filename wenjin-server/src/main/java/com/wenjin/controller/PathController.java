package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.config.AccessGuard;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.LearningPathVO;
import com.wenjin.dto.PathGenerateRequest;
import com.wenjin.service.CourseService;
import com.wenjin.service.PathService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学习路径接口（PRD 8.3）：生成 / 加载当前 / 标记完成。
 */
@RestController
@RequestMapping("/api/path")
@Validated
public class PathController {

    private final PathService pathService;
    private final CourseService courseService;

    public PathController(PathService pathService, CourseService courseService) {
        this.pathService = pathService;
        this.courseService = courseService;
    }

    /** 生成（重算）学习路径。POST /api/path/generate */
    @PostMapping("/generate")
    public Result<LearningPathVO> generate(@Valid @RequestBody PathGenerateRequest req) {
        long studentId = AccessGuard.requireSelfStudent(req.getStudentId());
        req.setStudentId(studentId);
        courseService.assertAccessibleByStudent(studentId, req.getCourseId());
        return Result.ok(pathService.generate(req));
    }

    /** 加载当前有效路径。GET /api/path/current?courseId=  （可选兼容参数 studentId） */
    @GetMapping("/current")
    public Result<LearningPathVO> current(
            @RequestParam(value = "studentId", required = false) Long studentId,
            @RequestParam("courseId") @Positive Long courseId) {
        long id = AccessGuard.requireSelfStudent(studentId);
        courseService.assertAccessibleByStudent(id, courseId);
        return Result.ok(pathService.getCurrent(id, courseId));
    }

    /** 标记某步完成。POST /api/path/item/complete?itemId= */
    @PostMapping("/item/complete")
    public Result<Void> complete(@RequestParam("itemId") @Positive Long itemId) {
        CurrentUser.requireStudent();
        pathService.completeItem(itemId);
        return Result.ok();
    }
}
