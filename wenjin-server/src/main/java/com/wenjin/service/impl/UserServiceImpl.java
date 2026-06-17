package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.LoginRequest;
import com.wenjin.dto.RegisterRequest;
import com.wenjin.dto.UserVO;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.service.CourseService;
import com.wenjin.service.UserService;
import org.springframework.stereotype.Service;

/**
 * 用户服务实现。
 */
@Service
public class UserServiceImpl implements UserService {

    private final SysUserMapper userMapper;
    private final CourseService courseService;

    public UserServiceImpl(SysUserMapper userMapper, CourseService courseService) {
        this.userMapper = userMapper;
        this.courseService = courseService;
    }

    @Override
    public UserVO register(RegisterRequest request) {
        // 检查用户名是否已存在
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.getUsername()));
        if (count > 0) {
            throw new BusinessException(ResultCode.USER_EXISTS, "用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(request.getPassword());
        user.setRealName(request.getRealName());
        user.setRole(request.getRole());
        user.setStatus(1);
        userMapper.insert(user);

        // 学生注册后自动选课
        if (user.getRole() != null && user.getRole() == 2) {
            courseService.autoEnrollAll(user.getId());
        }

        return toVO(user);
    }

    @Override
    public UserVO login(LoginRequest request) {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, request.getUsername())
                        .eq(SysUser::getPassword, request.getPassword()));
        if (user == null) {
            throw new BusinessException(ResultCode.LOGIN_FAIL, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.LOGIN_FAIL, "账号已被禁用");
        }
        return toVO(user);
    }

    @Override
    public UserVO getUserById(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return toVO(user);
    }

    private UserVO toVO(SysUser user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        return vo;
    }
}
