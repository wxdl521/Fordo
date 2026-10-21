package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 班级实体（clazz 表，管理后台维护）。班级是"批量选课"的手段，不替代学生自选。
 */
@Data
@TableName("clazz")
public class Clazz {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 班级名（唯一），如 软件工程2201 */
    private String name;

    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
