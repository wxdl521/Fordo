package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.CreateCourseRequest;
import com.wenjin.dto.TeacherCourseVO;
import com.wenjin.service.TeacherCourseService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 老师端课程管理（受 TeacherAuthInterceptor 保护）。 */
@RestController
@RequestMapping("/api/teacher/courses")
public class TeacherCourseController {

    private final TeacherCourseService teacherCourseService;

    public TeacherCourseController(TeacherCourseService teacherCourseService) {
        this.teacherCourseService = teacherCourseService;
    }

    @GetMapping
    public Result<List<TeacherCourseVO>> list() {
        return Result.ok(teacherCourseService.list());
    }

    @PostMapping
    public Result<TeacherCourseVO> create(@RequestBody CreateCourseRequest req,
                                          @RequestHeader(value = "X-User-Id", required = false) Long teacherId) {
        return Result.ok(teacherCourseService.create(req.getName(), teacherId));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        teacherCourseService.delete(id);
        return Result.ok();
    }
}
