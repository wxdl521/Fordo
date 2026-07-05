package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 今日复习队列（GET /api/review/today）。
 * <p>队列由读时衰减定义：raw 曾达 mastered 但 effective 已跌破 mastered 阈值的节点。</p>
 */
@Data
public class ReviewTodayVO {

    private List<ReviewItemVO> items;
    /** 截断前全量衰减节点数 */
    private int totalDecayingCount;

    @Data
    public static class ReviewItemVO {
        private Long nodeId;
        private String nodeCode;
        private String name;
        private String chapter;
        private Double rawScore;
        private Double effectiveScore;
        /** 2=已掌握 / 1=薄弱 / 0=未学（由 effectiveScore 重算） */
        private Integer effectiveLevel;
        /** 距上次证据（updated_at）的天数，可为小数 */
        private Double daysSinceEvidence;
        /** 排序分：(raw − effective) × (1 + 0.5·isKey) × (1 + 0.1·出度) */
        private Double urgency;
        /** 已审核且关联该节点的可用练习题数；0 时前端标「暂无练习题」 */
        private Integer availableQuestions;
    }
}