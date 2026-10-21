package com.wenjin.controller;

import com.wenjin.audit.Audited;
import com.wenjin.common.Result;
import com.wenjin.dto.AdminChangeStatusRequest;
import com.wenjin.dto.AdminCreateUserRequest;
import com.wenjin.dto.AdminResetPasswordRequest;
import com.wenjin.dto.AdminResetPasswordVO;
import com.wenjin.dto.AdminUpdateUserRequest;
import com.wenjin.dto.AdminUserImportResultVO;
import com.wenjin.dto.AdminUserPageVO;
import com.wenjin.dto.AdminUserVO;
import com.wenjin.service.AdminUserImportService;
import com.wenjin.service.AdminUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * 管理后台用户管理（受 {@code AdminAuthInterceptor} 保护，仅系统管理员）。
 * 薄层：参数校验与委托；业务规则全部在 {@link AdminUserService} / {@link AdminUserImportService}。
 * 角色变更仍由 {@link SystemAdminUserController} 的 {@code PATCH /users/{id}/role} 提供。
 */
@RestController
@RequestMapping("/api/system-admin")
@Validated
public class SystemAdminUserManageController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final AdminUserService userService;
    private final AdminUserImportService importService;

    public SystemAdminUserManageController(AdminUserService userService, AdminUserImportService importService) {
        this.userService = userService;
        this.importService = importService;
    }

    /** GET /api/system-admin/users */
    @GetMapping("/users")
    public Result<AdminUserPageVO> list(@RequestParam(value = "keyword", required = false) String keyword,
                                        @RequestParam(value = "role", required = false) Integer role,
                                        @RequestParam(value = "status", required = false) Integer status,
                                        @RequestParam(value = "page", required = false) Integer page,
                                        @RequestParam(value = "size", required = false) Integer size) {
        return Result.ok(userService.list(keyword, role, status, page, size));
    }

    /** POST /api/system-admin/users */
    @Audited(action = "USER_CREATE", target = "USER")
    @PostMapping("/users")
    public Result<AdminUserVO> create(@Valid @RequestBody AdminCreateUserRequest request) {
        return Result.ok(userService.create(request));
    }

    /** PUT /api/system-admin/users/{id} */
    @Audited(action = "USER_UPDATE", target = "USER")
    @PutMapping("/users/{id}")
    public Result<AdminUserVO> update(@PathVariable("id") @Positive Long id,
                                      @Valid @RequestBody AdminUpdateUserRequest request) {
        return Result.ok(userService.updateRealName(id, request));
    }

    /** PATCH /api/system-admin/users/{id}/status */
    @Audited(action = "USER_STATUS", target = "USER")
    @PatchMapping("/users/{id}/status")
    public Result<AdminUserVO> changeStatus(@PathVariable("id") @Positive Long id,
                                            @Valid @RequestBody AdminChangeStatusRequest request) {
        return Result.ok(userService.changeStatus(id, request.getStatus()));
    }

    /** POST /api/system-admin/users/{id}/reset-password（body 可省略） */
    @Audited(action = "USER_RESET_PASSWORD", target = "USER")
    @PostMapping("/users/{id}/reset-password")
    public Result<AdminResetPasswordVO> resetPassword(
            @PathVariable("id") @Positive Long id,
            @Valid @RequestBody(required = false) AdminResetPasswordRequest request) {
        String newPassword = request == null ? null : request.getNewPassword();
        return Result.ok(userService.resetPassword(id, newPassword));
    }

    /** GET /api/system-admin/users/import-template：xlsx 附件。 */
    @GetMapping("/users/import-template")
    public ResponseEntity<byte[]> importTemplate() {
        byte[] bytes = importService.buildTemplate();
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("用户导入模板.xlsx", StandardCharsets.UTF_8).build().toString())
                .contentLength(bytes.length)
                .body(bytes);
    }

    /**
     * POST /api/system-admin/users/import：multipart 字段 {@code file}。
     * 有错误行时仍返回 200 + {created:0, errors:[...]}（全有或全无，一行不写）。
     * file 声明为可选，缺失时由 service 给出清晰的 400，而不是框架层的 500。
     */
    @Audited(action = "USER_IMPORT", target = "USER")
    @PostMapping("/users/import")
    public Result<AdminUserImportResultVO> importUsers(@RequestParam(value = "file", required = false) MultipartFile file) {
        return Result.ok(importService.importUsers(file));
    }
}
