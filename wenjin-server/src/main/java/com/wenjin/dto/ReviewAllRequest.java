package com.wenjin.dto;

import lombok.Data;

/**
 * 服务端全量审批请求 DTO（T6：前端传筛选条件，服务端单事务批量更新）。
 */
@Data
public class ReviewAllRequest {
    /** 题目状态过滤（0=待审核 / 1=已通过 / 2=已驳回，null=不限）*/
    private Integer status;
    /** 置信度区间过滤（ge85 / mid / lt70，null=不限）*/
    private String conf;
    /** 知识点过滤（主考点 nodeCode，null=不限）*/
    private String nodeCode;
    /** 审核动作（pass 或 reject，必填）*/
    private String action;
}
