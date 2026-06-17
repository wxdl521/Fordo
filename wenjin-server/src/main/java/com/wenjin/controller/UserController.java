package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.LoginRequest;
import com.wenjin.dto.RegisterRequest;
import com.wenjin.dto.UserVO;
import com.wenjin.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户注册/登录接口。
 */
@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 注册。
     * POST /api/register
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
    public Result<UserVO> login(@RequestBody LoginRequest request) {
        return Result.ok(userService.login(request));
    }

    /**
     * 查询用户信息。
     * GET /api/user/{id}
     */
    @GetMapping("/user/{id}")
    public Result<UserVO> getUser(@PathVariable("id") Long id) {
        return Result.ok(userService.getUserById(id));
    }
}
