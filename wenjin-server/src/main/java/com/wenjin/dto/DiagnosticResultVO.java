package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 诊断回溯结果 VO：单一卡点 + 回溯链 + 根因 + 依据 + 待验证 + 掌握度分布/覆盖。
 * 对前端只暴露 nodeCode，不含内部主键。
 */
@Data
public class DiagnosticResultVO {

    /** 是否存在薄弱点（false 时结论相关字段为空，仅 distribution/coverage 有效） */
    private boolean hasWeakness;

    /** 卡点（最下游薄弱点） */
    private NodeRef stuckNode;

    /** 根因 */
    private RootCause rootCause;

    /** 回溯链：根因 → … → 卡点 */
    private List<ChainNode> chain;

    /** 结论文案 */
    private String conclusionText;

    /** 判断依据（按序，1–3 条） */
    private List<BasisItem> bases;

    /** 嫌疑根因（≤3，按 距离权重×缺口 降序） */
    private List<NodeRef> suspects;

    /** 待验证前置点（无作答数据）+ 推送的验证题 */
    private List<PendingNode> pendingVerification;

    /** 掌握度分布（按课程全节点三态计数） */
    private Distribution distribution;

    /** 覆盖（有作答数据的节点 / 总节点） */
    private Coverage coverage;

    /** 已作答题次（answer_record 条数） */
    private int questionsAnswered;

    @Data
    public static class NodeRef {
        private String nodeCode;
        private String name;
        private String chapter;
        private Double masteryScore;   // 无数据时 null
        private Integer masteryLevel;  // 无数据时 null
    }

    @Data
    public static class RootCause {
        private String nodeCode;
        private String name;
        private Double masteryScore;
        private Integer masteryLevel;
        private boolean self;          // 根因==卡点
    }

    @Data
    public static class ChainNode {
        private String nodeCode;
        private String name;
        private Double masteryScore;
        private Integer masteryLevel;
        private String role;           // root | middle | stuck
    }

    @Data
    public static class BasisItem {
        private String order;          // 一 / 二 / 三
        private String text;
        private String sub;
        private Double score;
        private Integer level;
    }

    @Data
    public static class PendingNode {
        private String nodeCode;
        private String name;
        private List<Long> suggestedQuestionIds;
    }

    @Data
    public static class Distribution {
        private int mastered;
        private int weak;
        private int unlearned;
    }

    @Data
    public static class Coverage {
        private int covered;
        private int total;
    }
}
