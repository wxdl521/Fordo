package com.wenjin.dto;

import lombok.Data;

/**
 * 审计日志视图（管理后台「操作日志」）。
 */
@Data
public class AuditLogVO {

    private Long id;
    /** 操作人 sys_user.id；匿名为 null */
    private Long operatorId;
    private String operatorName;
    /** 1=teacher, 2=student, 3=admin；匿名为 null */
    private Integer operatorRole;
    /** 动作编码，如 USER_CREATE */
    private String action;
    /** USER/COURSE/GRAPH/QUESTION/RAG */
    private String targetType;
    private String targetId;
    private String httpMethod;
    private String path;
    private Integer httpStatus;
    private boolean success;
    private String ip;
    private String requestId;
    /** ISO-8601 本地时间字符串（如 2026-10-21T10:15:30） */
    private String createdAt;
}
