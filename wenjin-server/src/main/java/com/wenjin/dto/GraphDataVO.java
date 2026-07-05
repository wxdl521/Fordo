package com.wenjin.dto;

import lombok.Data;

import java.util.List;

/**
 * 图谱查询返回体（GET /api/graph/{courseId}）。供前端染色地图渲染。
 */
@Data
public class GraphDataVO {

    private CourseVO course;
    private List<NodeVO> nodes;
    private List<EdgeVO> edges;

    @Data
    public static class CourseVO {
        private Long id;
        private String code;
        private String name;
    }

    /** 节点（含掌握度三态与分值；带 studentId 时按真实学情填充，否则统一 unlearned） */
    @Data
    public static class NodeVO {
        /** 业务编码，作为前端图节点的唯一 id */
        private String nodeCode;
        private String name;
        private String chapter;
        private Integer difficulty;
        /** 是否重点 */
        private Boolean isKey;
        private String description;
        /**
         * 染色用掌握度三态：mastered / weak / unlearned。
         * 有 studentId 时由 {@link #effectiveLevel} 映射；否则统一 unlearned。
         */
        private String mastery;
        /**
         * 数据库原始掌握度分值（0–100，向后兼容字段，等同 {@link #rawScore}）。
         * 无学生上下文或无掌握行时为 null。
         */
        private Double masteryScore;
        /** 数据库原始分 {@code student_mastery.mastery_score}；无掌握行时为 null */
        private Double rawScore;
        /** 读时衰减后的有效分；衰减关闭时与 rawScore 相同 */
        private Double effectiveScore;
        /** 由 effectiveScore 重算的掌握等级：2=已掌握 / 1=薄弱 / 0=未学 */
        private Integer effectiveLevel;
        /** 距上次证据（updated_at）的天数，可为小数；无掌握行时为 null */
        private Double daysSinceEvidence;
    }

    /** 边（source/target 用节点业务编码，type 为中文标签） */
    @Data
    public static class EdgeVO {
        private String source;
        private String target;
        /** 关系类型中文标签：前置/包含/相关/应用 */
        private String type;
    }
}
