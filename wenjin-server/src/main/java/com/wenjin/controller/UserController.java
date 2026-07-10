package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.LoginRequest;
import com.wenjin.dto.LoginVO;
import com.wenjin.dto.RegisterRequest;
import com.wenjin.dto.UserVO;
import com.wenjin.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户注册/登录/当前会话接口。
 */
@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 公开注册（仅学生）。
     * POST /api/register
     * <p>客户端不得指定角色：遗留 JSON 字段 {@code role} 在 DTO 中仅兼容接收并忽略，
     * 服务端无条件写入学生角色。教师/管理员开通见运维流程（DEPLOYMENT.md）或 ADMIN 入口。
     */
    @PostMapping("/register")
    public Result<UserVO> register(@RequestBody RegisterRequest request) {
        return Result.ok(userService.register(request));
    }

    /**
     * 登录。
     * POST /api/login
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody LoginRequest request) {
        return Result.ok(userService.login(request));
    }

    /**
     * 当前登录用户信息（取代公开 {@code GET /api/user/{id}} 枚举）。
     * GET /api/me
     */
    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(userService.getUserById(CurrentUser.require().userId()));
    }
}
