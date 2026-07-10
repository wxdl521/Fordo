package com.wenjin.security;

/**
 * 请求级可信主体：用户 ID + 数据库角色（不信任令牌内历史角色）。
 */
public record CurrentPrincipal(long userId, UserRole role) {

    public boolean isStudent() {
        return role == UserRole.STUDENT;
    }

    public boolean isTeacher() {
        return role == UserRole.TEACHER;
    }

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }
}
