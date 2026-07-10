package com.wenjin.security;

import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 按用户 ID 回查 {@code sys_user}，仅接受启用（status=1）且角色可识别的用户。
 * 授权使用数据库角色，不信任令牌内历史角色。
 */
@Component
public class PrincipalLoader {

    private static final int STATUS_ACTIVE = 1;

    private final SysUserMapper userMapper;

    public PrincipalLoader(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * @return 可信主体；用户不存在、已禁用或角色未知时为空
     */
    public Optional<CurrentPrincipal> load(long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return Optional.empty();
        }
        if (user.getStatus() == null || user.getStatus() != STATUS_ACTIVE) {
            return Optional.empty();
        }
        if (user.getRole() == null) {
            return Optional.empty();
        }
        try {
            UserRole role = UserRole.fromCode(user.getRole());
            return Optional.of(new CurrentPrincipal(userId, role));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
