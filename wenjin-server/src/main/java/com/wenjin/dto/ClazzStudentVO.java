package com.wenjin.dto;

import lombok.Data;

/** 班级成员（学生）。 */
@Data
public class ClazzStudentVO {

    private Long id;
    private String username;
    private String realName;
    /** 1=启用, 0=禁用 */
    private Integer status;
}
