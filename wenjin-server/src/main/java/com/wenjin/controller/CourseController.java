package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.config.AccessGuard;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.CourseWithMasteryVO;
import com.wenjin.dto.EnrollRequest;
import com.wenjin.entity.Course;
import com.wenjin.service.CourseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 选课接口。
 */
@RestController
@RequestMapping("/api/course")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    /**
     * 学生选课。身份取自 principal，请求体仅含 courseId。
     * POST /api/course/enroll
     */
    @PostMapping("/enroll")
    public Result<Void> enroll(@Valid @RequestBody EnrollRequest request) {
        long studentId = CurrentUser.requireStudent().userId();
        courseService.enroll(studentId, request.getCourseId());
        return Result.ok();
    }

    /**
     * 查询学生已选课程列表（含掌握度统计）。
     * GET /api/course/my  （可选兼容参数 studentId：若存在且 ≠ principal → 403）
     */
    @GetMapping("/my")
    public Result<List<CourseWithMasteryVO>> getMyCourses(
            @RequestParam(value = "studentId", required = false) Long studentId) {
        long id = AccessGuard.requireSelfStudent(studentId);
        return Result.ok(courseService.getMyCourses(id));
    }

    /**
     * 查询所有可用课程。
     * GET /api/course/available
     */
    @GetMapping("/available")
    public Result<List<Course>> getAvailableCourses() {
        return Result.ok(courseService.getAvailableCourses());
    }
}
