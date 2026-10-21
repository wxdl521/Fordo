package com.wenjin.dto;

import lombok.Data;

/** 管理后台"任课教师"下拉项（仅启用中的教师）。 */
@Data
public class AdminTeacherVO {

    private Long id;
    private String username;
    private String realName;

    public AdminTeacherVO() {}

    public AdminTeacherVO(Long id, String username, String realName) {
        this.id = id;
        this.username = username;
        this.realName = realName;
    }
}
