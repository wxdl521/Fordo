package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 自适应诊断响应（M2 T4）：start 与 answer 共用同一形状，前端好写。
 *
 * <ul>
 *   <li>未终止：{@code done=false}，{@code probe} 为下一探针，{@code summary/resultUrl} 为 null；</li>
 *   <li>已终止：{@code done=true}，{@code probe} 为 null，{@code summary} 为诊断摘要，{@code resultUrl} 指向结果页。</li>
 * </ul>
 *
 * <p>已锁决策：探针只下发 nodeId + 结构性脱敏题目，<b>不下发 nodeName</b>（防引导作弊），
 * 进度只给数字。
 */
@Data
public class AdaptiveDiagnosticVO {

    /** 会话 ID（后续 answer/abandon 用）。 */
    private Long sessionId;

    /** 是否已终止（诊断结束）。 */
    private boolean done;

    /** 当前/下一探针（终止时为 null）。 */
    private ProbeVO probe;

    /** 进度（纯数字）。 */
    private ProgressVO progress;

    /** 诊断摘要（仅终止时非 null；T4 为四类计数 + 空节点明细，T5 填充节点明细）。 */
    private SummaryVO summary;

    /** 结果页地址（仅终止时非 null；T4 固定 /diagnostic/result?courseId=...，T8 增强）。 */
    private String resultUrl;

    /**
     * 探针视图：被探节点 id + 脱敏题目。
     * <b>绝不含 nodeName</b>（防学生据知识点名反推答案）。
     */
    @Data
    public static class ProbeVO {

        /** 被探针的知识点 id（不下发名称）。 */
        private Long nodeId;

        /** 脱敏题目（无 answer/isCorrect/pointNodeCode）。 */
        private List<PaperQuestionVO> questions;
    }

    /** 进度（纯数字，不泄露节点身份）。 */
    @Data
    public static class ProgressVO {

        /** 已实测节点数（TESTED_PASS + TESTED_FAIL）。 */
        private int tested;

        /** 已推断节点数（INFERRED_PASS + INFERRED_FAIL）。 */
        private int inferred;

        /** 仍未知节点数。 */
        private int unknown;

        /** 剩余题量预算（budget - 已用）。 */
        private int budgetLeft;
    }

    /**
     * 诊断摘要（终止收尾）。
     *
     * <p>T4 桩：四类计数来自引擎 summary，{@code nodes} 为空列表。
     * T5 将在 finalizeSession 落库推断保守初值 + 快照，并填充 {@code nodes} 节点明细。
     */
    @Data
    public static class SummaryVO {

        private int tested;
        private int inferredPass;
        private int inferredFail;
        private int unknown;

        /** 节点明细（T4 恒空，T5 填充）。 */
        private List<NodeDetailVO> nodes;
    }

    /** 节点明细占位（T5 填充：诊断结论的逐节点状态展示）。 */
    @Data
    public static class NodeDetailVO {

        private Long nodeId;
        private String nodeCode;
        private String name;

        /** 节点状态名（TESTED_PASS / INFERRED_FAIL / ...）。 */
        private String state;
    }
}
