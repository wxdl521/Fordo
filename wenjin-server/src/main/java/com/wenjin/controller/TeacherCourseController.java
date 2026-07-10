package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.CreateCourseRequest;
import com.wenjin.dto.TeacherCourseVO;
import com.wenjin.dto.UpdateCourseStatusRequest;
import com.wenjin.service.TeacherCourseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 老师端课程管理（受 TeacherAuthInterceptor 保护；owner 由服务层从 CurrentUser 取）。 */
@RestController
@RequestMapping("/api/teacher/courses")
@Validated
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
    public Result<TeacherCourseVO> create(@Valid @RequestBody CreateCourseRequest req) {
        return Result.ok(teacherCourseService.create(req.getName()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable @Positive Long id) {
        teacherCourseService.delete(id);
        return Result.ok();
    }

    @PatchMapping("/{id}/status")
    public Result<Void> setStatus(@PathVariable @Positive Long id,
                                  @Valid @RequestBody UpdateCourseStatusRequest req) {
        teacherCourseService.setPublished(id, Boolean.TRUE.equals(req.getPublished()));
        return Result.ok();
    }
}
