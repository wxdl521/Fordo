package com.wenjin.dto;

/**
 * 节点创建/更新请求
 */
public class NodeUpsertRequest {
    private String nodeCode;
    private String name;
    private String chapter;
    private Integer difficulty;
    private Boolean isKey;
    private String description;
    private String note;

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
