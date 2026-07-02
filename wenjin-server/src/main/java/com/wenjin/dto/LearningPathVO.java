package com.wenjin.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 学习路径 VO（对前端只暴露 nodeCode）。
 */
@Data
public class LearningPathVO {

    private Long pathId;
    private NodeRef targetNode;
    private String conclusionText;
    private List<StepVO> steps;
    private Progress progress;

    @Data
    public static class NodeRef {
        private String nodeCode;
        private String name;
    }

    @Data
    public static class StepVO {
        private Long itemId;
        /** 节点数据库 ID（kg_node.id），供前端调用 /api/practice/start 使用。 */
        private Long nodeId;
        private String nodeCode;
        private String name;
        private String chapter;
        private Double masteryScore;
        private Integer masteryLevel;
        private int stepOrder;
        private int status;            // 0 未学 / 1 已完成
        private LocalDateTime completedAt;
        private String reason;
        private String role;           // root | prereq | stuck
        /** 该节点当前可用练习题数（status=1 且经 question_node 关联，不扣除近期已答）。供前端决定是否显示"去练习"按钮。 */
        private int availableQuestionCount;
    }

    @Data
    public static class Progress {
        private int done;
        private int total;
    }
}
