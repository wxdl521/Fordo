package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.CourseWithMasteryVO;
import com.wenjin.dto.EnrollRequest;
import com.wenjin.entity.Course;
import com.wenjin.service.CourseService;
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
     * 学生选课。
     * POST /api/course/enroll
     */
    @PostMapping("/enroll")
    public Result<Void> enroll(@RequestBody EnrollRequest request) {
        courseService.enroll(request.getStudentId(), request.getCourseId());
        return Result.ok();
    }

    /**
     * 查询学生已选课程列表（含掌握度统计）。
     * GET /api/course/my?studentId=
     */
    @GetMapping("/my")
    public Result<List<CourseWithMasteryVO>> getMyCourses(
            @RequestParam("studentId") Long studentId) {
        return Result.ok(courseService.getMyCourses(studentId));
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
