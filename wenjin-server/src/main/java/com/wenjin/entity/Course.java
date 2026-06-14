package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程实体（course 表）。一门课对应一张知识图谱。
 */
@Data
@TableName("course")
public class Course {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 课程业务编码（如 52015CC4B4），导入接口按此定位课程 */
    private String code;

    private String name;

    private String description;

    private Long teacherId;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
