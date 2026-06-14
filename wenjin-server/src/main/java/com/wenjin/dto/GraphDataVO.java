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

    /** 节点（带掌握度三态占位，本阶段统一 unlearned） */
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
        /** 掌握度三态：mastered / weak / unlearned（学情功能后续接入，当前固定 unlearned） */
        private String mastery;
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
