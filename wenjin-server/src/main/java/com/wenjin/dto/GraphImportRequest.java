package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 图谱导入请求体。对应 问津_软件工程图谱_v0.3.json 的整体结构：course + nodes + edges。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GraphImportRequest {

    private CourseMeta course;

    private List<NodeItem> nodes;

    private List<EdgeItem> edges;

    /** 课程元信息（导入时以 URL 参数 courseCode 为准，此处仅取课程名等） */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CourseMeta {
        private String code;
        private String name;
        @JsonProperty("graph_version")
        private String graphVersion;
    }

    /** 节点 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NodeItem {
        /** 业务ID，存入 kg_node.node_code */
        private String id;
        private String name;
        private String chapter;
        private Integer difficulty;
        @JsonProperty("is_key")
        private Boolean isKey;
        private String bloom;
        private String description;
        private String note;
    }

    /** 边 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EdgeItem {
        /** 起点业务ID */
        private String source;
        /** 终点业务ID */
        private String target;
        /** 关系类型中文标签：前置/包含/相关/应用 */
        private String type;
        private String note;
    }
}
