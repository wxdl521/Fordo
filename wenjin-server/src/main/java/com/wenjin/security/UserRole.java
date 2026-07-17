package com.wenjin.security;

/**
 * 系统用户角色常量。公开注册固定为学生；教师/管理员不得自助开通。
 * <p>编码约定：1=教师, 2=学生, 3=管理员。
 */
public enum UserRole {
    TEACHER(1),
    STUDENT(2),
    ADMIN(3);

    private final int code;

    UserRole(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static UserRole fromCode(int code) {
        for (UserRole role : values()) {
            if (role.code == code) {
                return role;
            }
        }
        throw new IllegalArgumentException("未知角色: " + code);
    }
}
