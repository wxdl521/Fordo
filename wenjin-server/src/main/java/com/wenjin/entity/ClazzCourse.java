package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 班级关联课程（clazz_course 表，(clazz_id, course_id) 唯一）。
 */
@Data
@TableName("clazz_course")
public class ClazzCourse {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long clazzId;

    private Long courseId;

    private LocalDateTime createdAt;
}
