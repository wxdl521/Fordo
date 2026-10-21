package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 今日复习手动置顶（review_pin 表）。
 */
@Data
@TableName("review_pin")
public class ReviewPin {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long studentId;

    private Long courseId;

    private LocalDate pinDate;

    private String nodeCode;

    /** TODAY / ADVANCE */
    private String mode;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
