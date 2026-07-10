package com.wenjin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 节点练习开始请求体（M1 练习闭环 T7 Controller 接线）。
 *
 * <p>鉴权由 Controller 层处理（assertSelf(studentId)）；
 * size 为 null 时由 service 取配置默认值，由校验层拒绝 size ≤ 0。</p>
 */
@Data
public class PracticeStartRequest {

    /** 学生 ID（鉴权用，须等于当前登录用户 ID） */
    private Long studentId;

    /** 课程 ID */
    @NotNull(message = "courseId 不能为空")
    @Positive(message = "courseId 必须为正数")
    private Long courseId;

    /** 目标知识点 ID（kg_node.id） */
    @NotNull(message = "nodeId 不能为空")
    @Positive(message = "nodeId 必须为正数")
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
     * 0 或负数由 Bean Validation 拒绝（BAD_REQUEST）。
     */
    @Min(value = 1, message = "size 必须为正整数")
    private Integer size;
}
