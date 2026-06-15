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
    }

    @Data
    public static class Progress {
        private int done;
        private int total;
    }
}
