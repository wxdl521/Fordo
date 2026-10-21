package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.AdminCreateUserRequest;
import com.wenjin.dto.AdminResetPasswordVO;
import com.wenjin.dto.AdminUpdateUserRequest;
import com.wenjin.dto.AdminUserPageVO;
import com.wenjin.dto.AdminUserVO;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.AdminUserService;
import com.wenjin.support.AdminUserRules;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 管理后台用户管理实现。
 *
 * <p>分页：项目未注册 MyBatis-Plus 分页插件，故手写 count + {@code LIMIT offset,size}（两个数值均为服务端
 * 钳制后的整数，无注入面）。写入一律用"只带变化字段"的补丁实体 {@code updateById}（MP 默认忽略 null 字段），
 * 既不会覆盖密码，也避开 LambdaUpdateWrapper 在纯单测中的 lambda 缓存问题。</p>
 */
@Service
public class AdminUserServiceImpl implements AdminUserService {

    private static final int STATUS_DISABLED = 0;
    private static final int STATUS_ACTIVE = 1;

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private static final int GENERATED_PASSWORD_LENGTH = 12;
    // 去掉易混字符 0/O、1/l/I
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String ALL = UPPER + LOWER + DIGITS;

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public AdminUserServiceImpl(SysUserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    // ───────────────────────── 查询 ─────────────────────────

    @Override
    public AdminUserPageVO list(String keyword, Integer role, Integer status, Integer page, Integer size) {
        if (role != null && (role < UserRole.TEACHER.code() || role > UserRole.ADMIN.code())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色筛选值无效");
        }
        if (status != null && status != STATUS_DISABLED && status != STATUS_ACTIVE) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "状态筛选值无效");
        }
        int effectivePage = (page == null || page < 1) ? 1 : page;
        int effectiveSize = (size == null || size < 1) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        long total = userMapper.selectCount(filter(keyword, role, status));
        List<AdminUserVO> records = Collections.emptyList();
        if (total > 0) {
            long offset = (long) (effectivePage - 1) * effectiveSize;
            List<SysUser> rows = userMapper.selectList(
                    filter(keyword, role, status)
                            .orderByDesc("id")
                            .last("LIMIT " + offset + "," + effectiveSize));
            records = new ArrayList<>(rows.size());
            for (SysUser row : rows) {
                records.add(toVO(row));
            }
        }

        AdminUserPageVO vo = new AdminUserPageVO();
        vo.setRecords(records);
        vo.setTotal(total);
        vo.setPage(effectivePage);
        vo.setSize(effectiveSize);
        return vo;
    }

    /** 字符串列名的 QueryWrapper：SQL 在纯单测里也可直接断言（Lambda 列名要到渲染 SQL 时才解析，需要 TableInfo）。 */
    private QueryWrapper<SysUser> filter(String keyword, Integer role, Integer status) {
        QueryWrapper<SysUser> w = new QueryWrapper<>();
        String k = keyword == null ? "" : keyword.trim();
        if (!k.isEmpty()) {
            String escaped = escapeLike(k);
            w.and(q -> q.like("username", escaped).or().like("real_name", escaped));
        }
        if (role != null) {
            w.eq("role", role);
        }
        if (status != null) {
            w.eq("status", status);
        }
        return w;
    }

    /** 转义 LIKE 通配符（MySQL 默认转义符为反斜杠），让用户输入的 % _ 按字面匹配。 */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ───────────────────────── 创建 ─────────────────────────

    @Override
    public AdminUserVO create(AdminCreateUserRequest request) {
        String username = request.getUsername() == null ? null : request.getUsername().trim();
        requireValid(AdminUserRules.checkUsername(username));
        requireValid(AdminUserRules.checkPassword(request.getPassword()));
        String realName = trimToNull(request.getRealName());
        requireValid(AdminUserRules.checkRealName(realName));
        UserRole role = AdminUserRules.assignableRole(request.getRole());
        if (role == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色仅允许教师或学生");
        }

        Long exists = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (exists != null && exists > 0) {
            throw usernameExists();
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRealName(realName);
        user.setRole(role.code());
        user.setStatus(STATUS_ACTIVE);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw usernameExists();
        }
        // created_at 由数据库默认值生成，回读一次让返回值带上真实创建时间
        SysUser saved = userMapper.selectById(user.getId());
        return toVO(saved != null ? saved : user);
    }

    // ───────────────────────── 改姓名 ─────────────────────────

    @Override
    public AdminUserVO updateRealName(Long id, AdminUpdateUserRequest request) {
        String realName = trimToNull(request.getRealName());
        if (realName == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "姓名不能为空");
        }
        requireValid(AdminUserRules.checkRealName(realName));

        SysUser user = requireUser(userMapper.selectById(id));
        SysUser patch = new SysUser();
        patch.setId(id);
        patch.setRealName(realName);
        userMapper.updateById(patch);

        user.setRealName(realName);
        return toVO(user);
    }

    // ───────────────────────── 启停 ─────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserVO changeStatus(Long id, Integer status) {
        if (status == null || (status != STATUS_DISABLED && status != STATUS_ACTIVE)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "状态只能是 0 或 1");
        }
        CurrentPrincipal actor = CurrentUser.require();
        if (status == STATUS_DISABLED && Long.valueOf(actor.userId()).equals(id)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不能禁用自己");
        }

        SysUser user = requireUser(userMapper.selectByIdForUpdate(id));
        if (status == STATUS_DISABLED && isActiveAdmin(user)) {
            assertAnotherActiveAdminRemains();
        }
        if (!status.equals(user.getStatus())) {
            SysUser patch = new SysUser();
            patch.setId(id);
            patch.setStatus(status);
            userMapper.updateById(patch);
            user.setStatus(status);
        }
        return toVO(user);
    }

    private static boolean isActiveAdmin(SysUser user) {
        return user.getRole() != null && user.getRole() == UserRole.ADMIN.code()
                && user.getStatus() != null && user.getStatus() == STATUS_ACTIVE;
    }

    /**
     * 锁定全部启用管理员行（避免两个并发请求都看到"还有 2 个"而同时禁用），至少要剩下一个以外的才放行。
     * 调用时目标本身就是启用中的管理员，所以 ≤1 意味着它是最后一个。
     */
    private void assertAnotherActiveAdminRemains() {
        List<SysUser> activeAdmins = userMapper.selectList(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getRole, UserRole.ADMIN.code())
                        .eq(SysUser::getStatus, STATUS_ACTIVE)
                        .last("FOR UPDATE"));
        if (activeAdmins == null || activeAdmins.size() <= 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "禁止禁用最后一个启用管理员");
        }
    }

    // ───────────────────────── 重置密码 ─────────────────────────

    @Override
    public AdminResetPasswordVO resetPassword(Long id, String newPassword) {
        boolean generated = newPassword == null || newPassword.isEmpty();
        if (!generated) {
            requireValid(AdminUserRules.checkPassword(newPassword));
        }
        requireUser(userMapper.selectById(id));

        String raw = generated ? generatePassword() : newPassword;
        SysUser patch = new SysUser();
        patch.setId(id);
        patch.setPassword(passwordEncoder.encode(raw));
        userMapper.updateById(patch);
        return new AdminResetPasswordVO(generated ? raw : null);
    }

    /** 12 位随机密码：至少含大写、小写、数字各一，字符集去掉易混字符。 */
    private String generatePassword() {
        char[] chars = new char[GENERATED_PASSWORD_LENGTH];
        chars[0] = UPPER.charAt(random.nextInt(UPPER.length()));
        chars[1] = LOWER.charAt(random.nextInt(LOWER.length()));
        chars[2] = DIGITS.charAt(random.nextInt(DIGITS.length()));
        for (int i = 3; i < chars.length; i++) {
            chars[i] = ALL.charAt(random.nextInt(ALL.length()));
        }
        // Fisher-Yates 洗牌，避免固定位置泄露结构
        for (int i = chars.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char t = chars[i];
            chars[i] = chars[j];
            chars[j] = t;
        }
        return new String(chars);
    }

    // ───────────────────────── 批量插入 ─────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void insertAll(List<SysUser> users) {
        try {
            for (SysUser user : users) {
                userMapper.insert(user);
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.USER_EXISTS, "用户名已存在（可能刚被其他人创建），请重新导入");
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    private static void requireValid(String errorMessage) {
        if (errorMessage != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, errorMessage);
        }
    }

    private static SysUser requireUser(SysUser user) {
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private static BusinessException usernameExists() {
        return new BusinessException(ResultCode.USER_EXISTS, "用户名已存在");
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static AdminUserVO toVO(SysUser user) {
        AdminUserVO vo = new AdminUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt() == null
                ? null
                : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(user.getCreatedAt()));
        return vo;
    }
}
