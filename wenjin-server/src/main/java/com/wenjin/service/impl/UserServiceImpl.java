package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.LoginRequest;
import com.wenjin.dto.LoginVO;
import com.wenjin.dto.RegisterRequest;
import com.wenjin.dto.UserVO;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.TokenService;
import com.wenjin.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户服务实现。
 */
@Service
public class UserServiceImpl implements UserService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public UserServiceImpl(SysUserMapper userMapper, PasswordEncoder passwordEncoder,
                           TokenService tokenService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
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
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRealName(request.getRealName());
        user.setRole(request.getRole());
        user.setStatus(1);
        userMapper.insert(user);

        return toVO(user);
    }

    @Override
    public LoginVO login(LoginRequest request) {
        // 按用户名查（密码不再进 SQL 条件，改为应用层哈希比对）
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, request.getUsername()));
        if (user == null || !passwordMatches(request.getPassword(), user)) {
            throw new BusinessException(ResultCode.LOGIN_FAIL, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.LOGIN_FAIL, "账号已被禁用");
        }
        LoginVO vo = new LoginVO();
        vo.setToken(tokenService.issue(user.getId(), user.getRole() == null ? 0 : user.getRole()));
        vo.setUser(toVO(user));
        return vo;
    }

    /**
     * 密码比对：bcrypt 哈希走 encoder；遗留明文按相等比对，命中则顺手重哈希落库（透明升级）。
     */
    private boolean passwordMatches(String raw, SysUser user) {
        String stored = user.getPassword();
        if (stored != null && stored.startsWith("$2")) {
            return passwordEncoder.matches(raw, stored);
        }
        boolean ok = raw != null && raw.equals(stored);
        if (ok) {
            user.setPassword(passwordEncoder.encode(raw));
            userMapper.updateById(user);
        }
        return ok;
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
