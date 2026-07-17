package com.wenjin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 教师端创建图谱边请求。
 * <p>relationType 使用数据库编码：1=前置, 2=包含, 3=相关, 4=应用。
 */
public class EdgeCreateRequest {

    @NotBlank(message = "起点节点编码不能为空")
    @Size(max = 64, message = "sourceNodeCode 长度不能超过 64")
    private String sourceNodeCode;

    @NotBlank(message = "终点节点编码不能为空")
    @Size(max = 64, message = "targetNodeCode 长度不能超过 64")
    private String targetNodeCode;

    @NotNull(message = "关系类型不能为空")
    @Min(value = 1, message = "relationType 须在 1-4 之间")
    @Max(value = 4, message = "relationType 须在 1-4 之间")
    private Integer relationType;

    @Size(max = 2000, message = "relationNote 长度不能超过 2000")
    private String relationNote;

    public String getSourceNodeCode() {
        return sourceNodeCode;
    }

    public void setSourceNodeCode(String sourceNodeCode) {
        this.sourceNodeCode = sourceNodeCode;
    }

    public String getTargetNodeCode() {
        return targetNodeCode;
    }

    public void setTargetNodeCode(String targetNodeCode) {
        this.targetNodeCode = targetNodeCode;
    }

    public Integer getRelationType() {
        return relationType;
    }

    public void setRelationType(Integer relationType) {
        this.relationType = relationType;
    }

    public String getRelationNote() {
        return relationNote;
    }

    public void setRelationNote(String relationNote) {
        this.relationNote = relationNote;
    }
}
