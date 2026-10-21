package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 班级成员（clazz_student 表）。student_id 全表唯一：一个学生至多属于一个班级。
 */
@Data
@TableName("clazz_student")
public class ClazzStudent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long clazzId;

    private Long studentId;

    private LocalDateTime createdAt;
}
