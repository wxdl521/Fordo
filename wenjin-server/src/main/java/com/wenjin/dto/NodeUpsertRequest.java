package com.wenjin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 节点创建/更新请求
 */
public class NodeUpsertRequest {

    @Size(max = 64, message = "nodeCode 长度不能超过 64")
    private String nodeCode;

    @NotBlank(message = "节点名称不能为空")
    @Size(max = 128, message = "节点名称长度不能超过 128")
    private String name;

    @Size(max = 128, message = "章节长度不能超过 128")
    private String chapter;

    @Min(value = 1, message = "difficulty 须在 1-5 之间")
    @Max(value = 5, message = "difficulty 须在 1-5 之间")
    private Integer difficulty;

    private Boolean isKey;

    @Size(max = 2000, message = "description 长度不能超过 2000")
    private String description;

    @Size(max = 2000, message = "note 长度不能超过 2000")
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
