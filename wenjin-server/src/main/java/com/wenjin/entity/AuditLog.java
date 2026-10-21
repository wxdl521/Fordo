package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关键操作审计日志（audit_log 表）。不设外键：日志要能比用户活得久。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人 sys_user.id；匿名为 null */
    private Long operatorId;

    /** 冗余用户名，防用户删改后无从追溯 */
    private String operatorName;

    /** 操作人角色：1=teacher, 2=student, 3=admin；匿名为 null */
    private Integer operatorRole;

    /** 动作编码，如 USER_CREATE */
    private String action;

    /** USER/COURSE/GRAPH/QUESTION/RAG */
    private String targetType;

    /** 取自路径变量 id/courseId/draftId */
    private String targetId;

    private String httpMethod;

    private String path;

    private Integer httpStatus;

    /** 1=成功（状态码 &lt;400 且无异常），0=失败 */
    private Integer success;

    private String ip;

    private String requestId;

    /** 缺省由数据库 DEFAULT CURRENT_TIMESTAMP 生成 */
    private LocalDateTime createdAt;
}
