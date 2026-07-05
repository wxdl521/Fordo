package com.wenjin.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自适应诊断证据 VO（M2 T8）。
 *
 * <p>归因来源：最近一次已完成（status=1）会话的 {@code state_json} 逐步回放，
 * 与存储终态核对一致后输出；<b>绝不从 student_mastery 反推</b>（后续练习会改掉那些值）。
 *
 * <p>计数字段（tested/inferredPass/inferredFail/unknown）从回放终态 nodeStates 数出；
 * {@code inferredNodes} 只含 INFERRED_* 节点，按 nodeId 升序。
 */
@Data
public class EvidenceVO {

    /** 会话 ID（对应 adaptive_diag_session.id）。 */
    private Long sessionId;

    /** 会话结束时间。 */
    private LocalDateTime finishedAt;

    /** 实测节点数（TESTED_PASS + TESTED_FAIL）。 */
    private int tested;

    /** 推断通过节点数（INFERRED_PASS）。 */
    private int inferredPass;

    /** 推断未通过节点数（INFERRED_FAIL）。 */
    private int inferredFail;

    /** 仍未知节点数（UNKNOWN）。 */
    private int unknown;

    /**
     * 推断节点明细（INFERRED_* 节点，按 nodeId 升序）。
     * 包含成因探针节点信息，可直接渲染证据文案。
     */
    private List<InferredNodeVO> inferredNodes;

    /**
     * 单条推断节点证据。
     * 成因 = 回放归因中最后一次使该节点进入 INFERRED_* 态的探针。
     */
    @Data
    public static class InferredNodeVO {

        /** 推断节点 ID。 */
        private Long nodeId;

        /**
         * 推断节点名（会话已结束，防作弊锁仅限进行中探针，此处合法下发）。
         */
        private String name;

        /** 推断状态名（INFERRED_PASS / INFERRED_FAIL）。 */
        private String state;

        /** 成因探针节点 ID（哪次探针的传播推断出了本节点）。 */
        private Long fromNodeId;

        /** 成因探针节点名。 */
        private String fromName;

        /** 成因探针是否答对（true=答对推断通过；false=答错推断失败）。 */
        private boolean fromPass;
    }
}
