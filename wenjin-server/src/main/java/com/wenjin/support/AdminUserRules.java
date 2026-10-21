package com.wenjin.support;

import com.wenjin.security.UserRole;

import java.nio.charset.StandardCharsets;

/**
 * 管理后台"创建用户"相关字段规则，供单个创建与批量导入共用（避免两处规则漂移）。
 * <p>校验方法返回错误文案，合法时返回 {@code null}，由调用方决定抛异常还是收集成行错误。</p>
 */
public final class AdminUserRules {

    public static final int USERNAME_MIN = 3;
    public static final int USERNAME_MAX = 64;
    public static final int PASSWORD_MIN = 8;
    public static final int PASSWORD_MAX = 72;
    public static final int REAL_NAME_MAX = 64;

    private AdminUserRules() {}

    /** 校验已 trim 的用户名。 */
    public static String checkUsername(String username) {
        if (username == null || username.isEmpty()) {
            return "用户名不能为空";
        }
        if (username.length() < USERNAME_MIN || username.length() > USERNAME_MAX) {
            return "用户名长度须在 " + USERNAME_MIN + "-" + USERNAME_MAX + " 之间";
        }
        return null;
    }

    /** 校验密码：8-72 字符；且 UTF-8 编码不超过 72 字节（BCrypt 上限）。密码不做 trim。 */
    public static String checkPassword(String password) {
        if (password == null || password.isEmpty()) {
            return "密码不能为空";
        }
        if (password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            return "密码长度须在 " + PASSWORD_MIN + "-" + PASSWORD_MAX + " 之间";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX) {
            return "密码过长（UTF-8 编码后不得超过 " + PASSWORD_MAX + " 字节）";
        }
        return null;
    }

    /** 校验已 trim 的真实姓名（可空）。 */
    public static String checkRealName(String realName) {
        if (realName != null && realName.length() > REAL_NAME_MAX) {
            return "真实姓名长度不能超过 " + REAL_NAME_MAX;
        }
        return null;
    }

    /** 管理后台可创建/指派的角色：仅教师(1)、学生(2)；其余返回 null。 */
    public static UserRole assignableRole(Integer code) {
        if (code == null) {
            return null;
        }
        if (code == UserRole.TEACHER.code()) {
            return UserRole.TEACHER;
        }
        if (code == UserRole.STUDENT.code()) {
            return UserRole.STUDENT;
        }
        return null;
    }
}
