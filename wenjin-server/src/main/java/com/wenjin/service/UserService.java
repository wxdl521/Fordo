package com.wenjin.service;

import com.wenjin.dto.LoginRequest;
import com.wenjin.dto.RegisterRequest;
import com.wenjin.dto.UserVO;

/**
 * 用户服务。
 */
public interface UserService {

    /**
     * 注册新用户。
     *
     * @param request 注册信息
     * @return 用户信息（不含密码）
     */
    UserVO register(RegisterRequest request);

    /**
     * 登录。
     *
     * @param request 登录信息
     * @return 用户信息（不含密码）
     */
    UserVO login(LoginRequest request);

    /**
     * 按 ID 查询用户。
     *
     * @param id 用户主键
     * @return 用户信息（不含密码）
     */
    UserVO getUserById(Long id);
}
