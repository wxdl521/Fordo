package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.LearningPathVO;
import com.wenjin.dto.PathGenerateRequest;
import com.wenjin.service.CourseService;
import com.wenjin.service.PathService;
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
public class PathController {

    private final PathService pathService;
    private final CourseService courseService;

    public PathController(PathService pathService, CourseService courseService) {
        this.pathService = pathService;
        this.courseService = courseService;
    }

    /** 生成（重算）学习路径。POST /api/path/generate */
    @PostMapping("/generate")
    public Result<LearningPathVO> generate(@RequestBody PathGenerateRequest req) {
        courseService.assertAccessibleByStudent(req.getStudentId(), req.getCourseId());
        return Result.ok(pathService.generate(req));
    }

    /** 加载当前有效路径。GET /api/path/current?studentId=&courseId= */
    @GetMapping("/current")
    public Result<LearningPathVO> current(@RequestParam("studentId") Long studentId,
                                          @RequestParam("courseId") Long courseId) {
        courseService.assertAccessibleByStudent(studentId, courseId);
        return Result.ok(pathService.getCurrent(studentId, courseId));
    }

    /** 标记某步完成。POST /api/path/item/complete?itemId= */
    @PostMapping("/item/complete")
    public Result<Void> complete(@RequestParam("itemId") Long itemId) {
        pathService.completeItem(itemId);
        return Result.ok();
    }
}
