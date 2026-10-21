package com.wenjin.controller;

import com.wenjin.audit.Audited;
import com.wenjin.common.Result;
import com.wenjin.dto.AdminPageVO;
import com.wenjin.dto.ClazzAddCourseRequest;
import com.wenjin.dto.ClazzAddStudentsRequest;
import com.wenjin.dto.ClazzAddStudentsResultVO;
import com.wenjin.dto.ClazzCourseEnrollVO;
import com.wenjin.dto.ClazzDetailVO;
import com.wenjin.dto.ClazzSaveRequest;
import com.wenjin.dto.ClazzVO;
import com.wenjin.service.AdminClazzService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台班级管理（受 {@code AdminAuthInterceptor} 保护，仅系统管理员）。
 * 薄层：参数校验与委托；业务规则在 {@link AdminClazzService}。
 *
 * <p>班级是"批量选课"的手段：加学生 / 关联课程时补 student_course；
 * 移除学生 / 解除课程 / 删除班级都不删除已有选课。</p>
 */
@RestController
@RequestMapping("/api/system-admin/clazzes")
@Validated
public class SystemAdminClazzController {

    private final AdminClazzService clazzService;

    public SystemAdminClazzController(AdminClazzService clazzService) {
        this.clazzService = clazzService;
    }

    /** GET /api/system-admin/clazzes：{@code size} 上限 100。 */
    @GetMapping
    public Result<AdminPageVO<ClazzVO>> list(@RequestParam(value = "keyword", required = false) String keyword,
                                             @RequestParam(value = "page", required = false) Integer page,
                                             @RequestParam(value = "size", required = false) Integer size) {
        return Result.ok(clazzService.list(keyword, page, size));
    }

    /** POST /api/system-admin/clazzes */
    @Audited(action = "CLAZZ_CREATE", target = "CLAZZ")
    @PostMapping
    public Result<ClazzVO> create(@Valid @RequestBody ClazzSaveRequest request) {
        return Result.ok(clazzService.create(request.getName(), request.getDescription()));
    }

    /** PUT /api/system-admin/clazzes/{id} */
    @Audited(action = "CLAZZ_UPDATE", target = "CLAZZ")
    @PutMapping("/{id}")
    public Result<ClazzVO> update(@PathVariable("id") @Positive Long id,
                                  @Valid @RequestBody ClazzSaveRequest request) {
        return Result.ok(clazzService.update(id, request.getName(), request.getDescription()));
    }

    /** DELETE /api/system-admin/clazzes/{id}：硬删班级及成员 / 课程关联，不动 student_course。 */
    @Audited(action = "CLAZZ_DELETE", target = "CLAZZ")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") @Positive Long id) {
        clazzService.delete(id);
        return Result.ok();
    }

    /** GET /api/system-admin/clazzes/{id}：基本信息 + students + courses。 */
    @GetMapping("/{id}")
    public Result<ClazzDetailVO> detail(@PathVariable("id") @Positive Long id) {
        return Result.ok(clazzService.detail(id));
    }

    /** POST /api/system-admin/clazzes/{id}/students：{@code studentIds} 或 {@code usernames} 二选一（≤500）。 */
    @Audited(action = "CLAZZ_STUDENT_ADD", target = "CLAZZ")
    @PostMapping("/{id}/students")
    public Result<ClazzAddStudentsResultVO> addStudents(@PathVariable("id") @Positive Long id,
                                                        @Valid @RequestBody ClazzAddStudentsRequest request) {
        return Result.ok(clazzService.addStudents(id, request.getStudentIds(), request.getUsernames()));
    }

    /** DELETE /api/system-admin/clazzes/{id}/students/{studentId}：保留其 student_course。 */
    @Audited(action = "CLAZZ_STUDENT_REMOVE", target = "CLAZZ")
    @DeleteMapping("/{id}/students/{studentId}")
    public Result<Void> removeStudent(@PathVariable("id") @Positive Long id,
                                      @PathVariable("studentId") @Positive Long studentId) {
        clazzService.removeStudent(id, studentId);
        return Result.ok();
    }

    /** POST /api/system-admin/clazzes/{id}/courses：{@code {courseId}}，返回新增的 student_course 行数。 */
    @Audited(action = "CLAZZ_COURSE_ADD", target = "CLAZZ")
    @PostMapping("/{id}/courses")
    public Result<ClazzCourseEnrollVO> addCourse(@PathVariable("id") @Positive Long id,
                                                 @Valid @RequestBody ClazzAddCourseRequest request) {
        return Result.ok(clazzService.addCourse(id, request.getCourseId()));
    }

    /** DELETE /api/system-admin/clazzes/{id}/courses/{courseId}：保留已有 student_course。 */
    @Audited(action = "CLAZZ_COURSE_REMOVE", target = "CLAZZ")
    @DeleteMapping("/{id}/courses/{courseId}")
    public Result<Void> removeCourse(@PathVariable("id") @Positive Long id,
                                     @PathVariable("courseId") @Positive Long courseId) {
        clazzService.removeCourse(id, courseId);
        return Result.ok();
    }
}
