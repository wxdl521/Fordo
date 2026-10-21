package com.wenjin.controller;

import com.wenjin.audit.ClientIp;
import com.wenjin.common.BusinessException;
import com.wenjin.common.LoginFailedException;
import com.wenjin.common.Result;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.LoginRequest;
import com.wenjin.dto.LoginVO;
import com.wenjin.dto.RegisterRequest;
import com.wenjin.dto.UserVO;
import com.wenjin.service.LoginLogService;
import com.wenjin.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
    private final LoginLogService loginLogService;

    public UserController(UserService userService, LoginLogService loginLogService) {
        this.userService = userService;
        this.loginLogService = loginLogService;
    }

    /**
     * 公开注册（仅学生）。
     * POST /api/register
     * <p>客户端不得指定角色：遗留 JSON 字段 {@code role} 在 DTO 中仅兼容接收并忽略，
     * 服务端无条件写入学生角色。教师/管理员开通见运维流程（DEPLOYMENT.md）或 ADMIN 入口。
     */
    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(userService.register(request));
    }

    /**
     * 登录。
     * POST /api/login
     * <p>成功与失败都写一条登录日志（失败原因 BAD_CREDENTIALS / DISABLED，不记密码）；
     * 失败时异常原样重抛，响应与未加日志前完全一致。日志写入失败由 {@link LoginLogService} 吞掉，不影响登录。
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String ip = ClientIp.resolve(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        try {
            LoginVO vo = userService.login(request);
            loginLogService.record(vo.getUser().getId(), request.getUsername(), true, null, ip, userAgent);
            return Result.ok(vo);
        } catch (BusinessException ex) {
            if (ex.getCode() == ResultCode.LOGIN_FAIL.getCode()) {
                Long userId = null;
                LoginFailedException.Reason reason = LoginFailedException.Reason.BAD_CREDENTIALS;
                if (ex instanceof LoginFailedException failed) {
                    userId = failed.getUserId();
                    reason = failed.getReason();
                }
                loginLogService.record(userId, request.getUsername(), false, reason.name(), ip, userAgent);
            }
            throw ex;
        }
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
