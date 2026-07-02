package com.wenjin.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 练习提交结果 VO（M1 练习闭环 T4–T6）。
 *
 * <p>字段分三类：
 * <ol>
 *   <li>T4 本任务填充：{@code graded}、{@code masteryBefore}、{@code masteryAfter}、
 *       {@code masteryLevel}。</li>
 *   <li>T5 填充：{@code weakPrerequisites}、{@code itemCompleted}。</li>
 *   <li>T6 填充（当前默认值）：{@code pathRegenerated}。</li>
 * </ol>
 */
@Data
public class PracticeSubmitVO {

    /** 逐题判分结果（顺序与提交 answers 一致） */
    private List<GradeItemVO> graded;

    /** 练习节点提交前的掌握度分值（0–100） */
    private Double masteryBefore;

    /** 练习节点提交后的掌握度分值（0–100） */
    private Double masteryAfter;

    /** 提交后掌握等级文字（"已掌握" / "薄弱" / "未学"） */
    private String masteryLevel;

    /** 路径步骤是否已完成（T5 填） */
    private boolean itemCompleted;

    /**
     * 薄弱前置知识点列表（T5 填）。
     * 每项包含 nodeCode、name、hitCount（该前置被错选命中的次数）。
     */
    private List<WeakPrerequisiteVO> weakPrerequisites;

    /** 路径是否已重算（T6 填，当前默认 false） */
    private boolean pathRegenerated;

    // ── 构造器：设 T5/T6 默认值 ───────────────────────────────────────────

    public PracticeSubmitVO() {
        this.itemCompleted = false;
        this.pathRegenerated = false;
        this.weakPrerequisites = new ArrayList<>();
    }

    // ── 内部类 ─────────────────────────────────────────────────────────────

    /** 单题判分详情 */
    @Data
    public static class GradeItemVO {

        /** 题目 ID */
        private Long questionId;

        /**
         * 是否答对；{@code null} 表示简答题不参与对错统计。
         */
        private Boolean correct;

        /**
         * 题目解析文本（取自 {@code question.analysis}），题库未提供时为 {@code null}。
         */
        private String analysis;

        /**
         * 正确答案选项标识：
         * 单选/判断 = 单字母；多选 = 逗号分隔已排序串；简答 = {@code null}。
         */
        private String correctAnswer;
    }

    /**
     * 薄弱前置知识点条目（distractor 归因聚合结果）。
     *
     * <p>命中次数 ≥ {@code wenjin.practice.distractor-threshold}（默认 2）才出现在列表中。
     */
    @Data
    public static class WeakPrerequisiteVO {

        /** 知识点业务编码（如 KT12） */
        private String nodeCode;

        /** 知识点名称 */
        private String name;

        /** 本次练习中该前置被错选命中的次数 */
        private int hitCount;
    }
}
