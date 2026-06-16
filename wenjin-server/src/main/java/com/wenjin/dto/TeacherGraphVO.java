package com.wenjin.dto;

import java.util.List;

/**
 * 教师端图谱视图（完整图谱，包含待复核边）
 */
public class TeacherGraphVO {
    private List<NodeVO> nodes;
    private List<EdgeVO> edges;

    public static class NodeVO {
        private Long id;
        private String nodeCode;
        private String name;
        private String chapter;
        private Integer difficulty;
        private Boolean isKey;
        private String description;
        private String note;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getNodeCode() { return nodeCode; }
        public void setNodeCode(String nodeCode) { this.nodeCode = nodeCode; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getChapter() { return chapter; }
        public void setChapter(String chapter) { this.chapter = chapter; }
        public Integer getDifficulty() { return difficulty; }
        public void setDifficulty(Integer difficulty) { this.difficulty = difficulty; }
        public Boolean getIsKey() { return isKey; }
        public void setIsKey(Boolean isKey) { this.isKey = isKey; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }

    public static class EdgeVO {
        private Long id;
        private String source;      // node_code (from)
        private String target;      // node_code (to)
        private String type;        // 前置/包含/相关/应用
        private String note;        // 去前缀后的理由（待复核边）或原备注
        private Integer confidence;
        private Boolean pending;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
        public Integer getConfidence() { return confidence; }
        public void setConfidence(Integer confidence) { this.confidence = confidence; }
        public Boolean getPending() { return pending; }
        public void setPending(Boolean pending) { this.pending = pending; }
    }

    public List<NodeVO> getNodes() { return nodes; }
    public void setNodes(List<NodeVO> nodes) { this.nodes = nodes; }
    public List<EdgeVO> getEdges() { return edges; }
    public void setEdges(List<EdgeVO> edges) { this.edges = edges; }
}
