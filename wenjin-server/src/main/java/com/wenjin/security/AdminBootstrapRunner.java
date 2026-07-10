package com.wenjin.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 一次性首个管理员引导：仅在 {@code wenjin.bootstrap.admin.enabled=true} 时启用。
 *
 * <p>要求配置非空 username 与至少 16 字符 password；仅当库中不存在任何 ADMIN 时创建
 * 一个 BCrypt 管理员。已存在 ADMIN 时拒绝再次引导。日志只写新用户 ID，不写密码。</p>
 *
 * <p>引导成功后应立即关闭开关并移除环境中的明文密码。</p>
 */
@Component
@ConditionalOnProperty(name = "wenjin.bootstrap.admin.enabled", havingValue = "true")
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);
    private static final int MIN_PASSWORD_LENGTH = 16;

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    private final String username;
    private final String password;

    public AdminBootstrapRunner(SysUserMapper userMapper,
                                PasswordEncoder passwordEncoder,
                                @Value("${wenjin.bootstrap.admin.username:}") String username,
                                @Value("${wenjin.bootstrap.admin.password:}") String password) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username == null || username.isBlank()) {
            throw new IllegalStateException(
                    "管理员引导已启用，但 wenjin.bootstrap.admin.username 为空；"
                            + "请通过环境变量 WENJIN_BOOTSTRAP_ADMIN_USERNAME 提供。");
        }
        if (password == null || password.isBlank() || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "管理员引导已启用，但 password 为空或长度不足 " + MIN_PASSWORD_LENGTH
                            + "；请通过环境变量 WENJIN_BOOTSTRAP_ADMIN_PASSWORD 提供。");
        }

        Long adminCount = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getRole, UserRole.ADMIN.code()));
        if (adminCount != null && adminCount > 0) {
            throw new IllegalStateException(
                    "管理员引导拒绝执行：数据库中已存在 ADMIN 账号，禁止再次引导。");
        }

        String trimmedUsername = username.trim();
        SysUser existing = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, trimmedUsername));
        if (existing != null) {
            throw new IllegalStateException(
                    "管理员引导拒绝执行：用户名已存在（username=" + trimmedUsername
                            + "）。请换用未占用的用户名，或使用运维 SQL 将已有账号提升为 ADMIN。");
        }

        SysUser admin = new SysUser();
        admin.setUsername(trimmedUsername);
        admin.setPassword(passwordEncoder.encode(password));
        admin.setRealName("系统管理员");
        admin.setRole(UserRole.ADMIN.code());
        admin.setStatus(1);
        try {
            userMapper.insert(admin);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // 并发建号或竞态下唯一键冲突：给出明确文案，避免启动日志堆栈只有原始 DB 错误
            throw new IllegalStateException(
                    "管理员引导拒绝执行：用户名已存在（username=" + trimmedUsername
                            + "）。请换用未占用的用户名，或使用运维 SQL 将已有账号提升为 ADMIN。",
                    ex);
        }

        log.info("Bootstrap admin created, id={}", admin.getId());
    }
}
