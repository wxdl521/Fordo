package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.config.AccessGuard;
import com.wenjin.dto.ReviewTodayVO;
import com.wenjin.service.CourseService;
import com.wenjin.service.ReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 复习队列接口（M3 §2.4）：今日衰减复习列表。
 */
@RestController
@RequestMapping("/api/review")
public class ReviewController {

    private final ReviewService reviewService;
    private final CourseService courseService;

    public ReviewController(ReviewService reviewService, CourseService courseService) {
        this.reviewService = reviewService;
        this.courseService = courseService;
    }

    /** 今日复习队列。GET /api/review/today?studentId=&courseId= */
    @GetMapping("/today")
    public Result<ReviewTodayVO> today(@RequestParam("studentId") Long studentId,
                                       @RequestParam("courseId") Long courseId) {
        AccessGuard.assertSelf(studentId);
        courseService.assertAccessibleByStudent(studentId, courseId);
        return Result.ok(reviewService.getToday(studentId, courseId));
    }
}