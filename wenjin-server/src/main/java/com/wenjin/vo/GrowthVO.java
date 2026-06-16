package com.wenjin.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 成长档案 VO：包含成长曲线和前后对比。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GrowthVO {

    /** 最早快照时间（曲线起点） */
    private LocalDateTime startAt;

    /** 成长曲线：每个快照时刻的整体掌握度 */
    private List<CurvePoint> curve;

    /** 前后对比：基线（首次）vs 当前（最新） */
    private Compare compare;

    /**
     * 曲线点：某次快照时刻的整体掌握度均值。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurvePoint {
        /** 快照时间 */
        private LocalDateTime snapshotAt;

        /** 整体掌握度（已触及节点的掌握度均值，保留 1 位小数） */
        private BigDecimal overallMastery;
    }

    /**
     * 前后对比数据。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Compare {
        /** 汇总统计 */
        private Summary summary;

        /** 基线（首次快照）各知识点按章节分组 */
        private List<ChapterPanel> baseline;

        /** 当前（最新快照）各知识点按章节分组 */
        private List<ChapterPanel> latest;
    }

    /**
     * 汇总统计。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        /** 基线整体掌握度 */
        private BigDecimal overallThen;

        /** 当前整体掌握度 */
        private BigDecimal overallNow;

        /** 基线掌握数量 */
        private Integer masteredThen;

        /** 当前掌握数量 */
        private Integer masteredNow;

        /** 基线薄弱数量 */
        private Integer weakThen;

        /** 当前薄弱数量 */
        private Integer weakNow;

        /** 基线未学数量 */
        private Integer unlearnedThen;

        /** 当前未学数量 */
        private Integer unlearnedNow;

        /** 转绿数量（基线未掌握 → 当前掌握） */
        private Integer turnedGreen;
    }

    /**
     * 章节面板：同一章节下的节点列表。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChapterPanel {
        /** 章节名 */
        private String chapter;

        /** 该章节下的节点等级列表 */
        private List<NodeLevel> nodes;
    }

    /**
     * 节点等级：单个知识点的掌握等级。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeLevel {
        /** 节点业务编码（如 KT12） */
        private String nodeCode;

        /** 节点名称 */
        private String name;

        /** 掌握等级：2=掌握, 1=薄弱, 0=未学 */
        private Integer level;
    }
}
