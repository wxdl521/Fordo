package com.wenjin.security;

import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Optional;

/**
 * 按用户 ID 回查 {@code sys_user}，仅接受启用（status=1）且角色可识别的用户。
 * 授权使用数据库角色，不信任令牌内历史角色。
 *
 * <p>令牌吊销：用户行的 {@code token_valid_after} 非空时，签发时间 {@code iatMs} 早于它的令牌一律无效（毫秒精度）
 * （管理员重置密码时写入）。旧令牌无 iatMs（=0）因此在被重置后同样失效；从未被重置过的用户不受影响。
 * 比较为严格小于、秒级精度：与水位线同一秒签发的令牌（重置后立即用新密码登录）仍有效。</p>
 */
@Component
public class PrincipalLoader {

    private static final int STATUS_ACTIVE = 1;

    private final SysUserMapper userMapper;

    public PrincipalLoader(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 不带令牌上下文的加载（如 AI 作业 worker 按 owner 还原身份），不做吊销水位线比较。
     *
     * @return 可信主体；用户不存在、已禁用或角色未知时为空
     */
    public Optional<CurrentPrincipal> load(long userId) {
        return loadInternal(userId, null);
    }

    /**
     * 令牌鉴权路径：在 {@link #load(long)} 的基础上再校验令牌签发时间是否被吊销。
     *
     * @param issuedAt 令牌 iatMs（epoch 毫秒）；升级前签发的旧令牌为 0
     * @return 可信主体；用户不存在、已禁用、角色未知或令牌已被吊销时为空
     */
    public Optional<CurrentPrincipal> load(long userId, long issuedAt) {
        return loadInternal(userId, issuedAt);
    }

    /** @param issuedAt 令牌 iatMs（epoch 毫秒）；null 表示没有令牌上下文，跳过吊销水位线比较 */
    private Optional<CurrentPrincipal> loadInternal(long userId, Long issuedAt) {
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
        if (issuedAt != null && user.getTokenValidAfter() != null
                && issuedAt < user.getTokenValidAfter().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) {
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
