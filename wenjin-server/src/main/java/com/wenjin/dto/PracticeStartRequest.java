package com.wenjin.dto;

import lombok.Data;

/**
 * 节点练习开始请求体（M1 练习闭环 T7 Controller 接线）。
 *
 * <p>鉴权由 Controller 层处理（assertSelf(studentId)）；
 * size 为 null 时由 service 取配置默认值，由 Controller 拒绝 size ≤ 0。</p>
 */
@Data
public class PracticeStartRequest {

    /** 学生 ID（鉴权用，须等于当前登录用户 ID） */
    private Long studentId;

    /** 课程 ID */
    private Long courseId;

    /** 目标知识点 ID（kg_node.id） */
    private Long nodeId;

    /**
     * 来源路径步骤 ID（可空）。
     * 非 null 时 service 校验归属（item→path→studentId==本人）并将 id 写入 practice_session.path_item_id，
     * 供提交后自动通过判定使用。null 表示自由练习。
     */
    private Long pathItemId;

    /**
     * 期望题数（可选，默认 5，上限 10）。
     * null = 取 wenjin.practice.size 配置默认值；
     * 正整数，超过 wenjin.practice.max-size 时由 service 自动夹紧。
     * 0 或负数由 Controller 拒绝（BAD_REQUEST）。
     */
    private Integer size;
}
