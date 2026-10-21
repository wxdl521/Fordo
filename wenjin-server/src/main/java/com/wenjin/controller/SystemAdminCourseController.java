package com.wenjin.controller;

import com.wenjin.audit.Audited;
import com.wenjin.common.Result;
import com.wenjin.dto.AdminCourseOwnerRequest;
import com.wenjin.dto.AdminCourseVO;
import com.wenjin.dto.AdminCreateCourseRequest;
import com.wenjin.dto.AdminPageVO;
import com.wenjin.dto.AdminTeacherVO;
import com.wenjin.dto.UpdateCourseStatusRequest;
import com.wenjin.service.AdminCourseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理后台课程管理（受 {@code AdminAuthInterceptor} 保护，仅系统管理员）。
 * 薄层：参数校验与委托；业务规则在 {@link AdminCourseService}。
 *
 * <p>发布 / 归档 / 恢复复用教师端同名审计动作（COURSE_STATUS / COURSE_DELETE / COURSE_RESTORE）：
 * 底层就是同一份 {@code TeacherCourseService} 逻辑，审计里按动作筛选时两端的记录归在一起；
 * 来源可由 path（/api/system-admin/... vs /api/teacher/...）与操作人角色区分。</p>
 */
@RestController
@RequestMapping("/api/system-admin")
@Validated
public class SystemAdminCourseController {

    private final AdminCourseService courseService;

    public SystemAdminCourseController(AdminCourseService courseService) {
        this.courseService = courseService;
    }

    /**
     * GET /api/system-admin/courses
     * <p>{@code archived}：true=仅归档, false=仅未归档, 省略或空=全部；{@code size} 上限 100。
     */
    @GetMapping("/courses")
    public Result<AdminPageVO<AdminCourseVO>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "archived", required = false) Boolean archived,
            @RequestParam(value = "teacherId", required = false) Long teacherId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return Result.ok(courseService.list(keyword, status, archived, teacherId, page, size));
    }

    /** POST /api/system-admin/courses：为指定的启用教师建课。 */
    @Audited(action = "COURSE_ADMIN_CREATE", target = "COURSE")
    @PostMapping("/courses")
    public Result<AdminCourseVO> create(@Valid @RequestBody AdminCreateCourseRequest request) {
        return Result.ok(courseService.create(request.getName(), request.getTeacherId()));
    }

    /** PATCH /api/system-admin/courses/{id}/owner：转让任课教师。 */
    @Audited(action = "COURSE_TRANSFER", target = "COURSE")
    @PatchMapping("/courses/{id}/owner")
    public Result<AdminCourseVO> transferOwner(@PathVariable("id") @Positive Long id,
                                               @Valid @RequestBody AdminCourseOwnerRequest request) {
        return Result.ok(courseService.transferOwner(id, request.getTeacherId()));
    }

    /** PATCH /api/system-admin/courses/{id}/status：{@code {published}} 发布 / 下架。 */
    @Audited(action = "COURSE_STATUS", target = "COURSE")
    @PatchMapping("/courses/{id}/status")
    public Result<AdminCourseVO> setStatus(@PathVariable("id") @Positive Long id,
                                           @Valid @RequestBody UpdateCourseStatusRequest request) {
        return Result.ok(courseService.setPublished(id, Boolean.TRUE.equals(request.getPublished())));
    }

    /** POST /api/system-admin/courses/{id}/archive：软归档。 */
    @Audited(action = "COURSE_DELETE", target = "COURSE")
    @PostMapping("/courses/{id}/archive")
    public Result<AdminCourseVO> archive(@PathVariable("id") @Positive Long id) {
        return Result.ok(courseService.archive(id));
    }

    /** POST /api/system-admin/courses/{id}/restore：恢复归档（恢复后为未发布）。 */
    @Audited(action = "COURSE_RESTORE", target = "COURSE")
    @PostMapping("/courses/{id}/restore")
    public Result<AdminCourseVO> restore(@PathVariable("id") @Positive Long id) {
        return Result.ok(courseService.restore(id));
    }

    /** GET /api/system-admin/teachers：启用中的教师，供下拉。 */
    @GetMapping("/teachers")
    public Result<List<AdminTeacherVO>> teachers() {
        return Result.ok(courseService.listTeachers());
    }
}
