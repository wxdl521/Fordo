package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 固定卷诊断会话（diagnostic_session）：组卷时冻结题集，提交时精确集合匹配 + 幂等结果。
 */
@Data
@TableName("diagnostic_session")
public class DiagnosticSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 客户端持有的会话令牌（UUID） */
    private String token;

    /** 学生（逻辑外键→sys_user.id） */
    private Long studentId;

    /** 课程（逻辑外键→course.id） */
    private Long courseId;

    /** 状态：0=进行中, 1=已完成 */
    private Integer status;

    /** 完成后幂等重放用的判分结果 JSON */
    private String resultJson;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
