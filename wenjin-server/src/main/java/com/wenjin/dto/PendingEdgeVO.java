package com.wenjin.dto;

/**
 * 待复核边详情（用于教师审核列表）
 */
public class PendingEdgeVO {
    private Long id;
    private String fromCode;
    private String fromName;
    private String toCode;
    private String toName;
    private String relationType;
    private Integer confidence;
    private String reason;
    private Boolean low;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFromCode() { return fromCode; }
    public void setFromCode(String fromCode) { this.fromCode = fromCode; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public String getToCode() { return toCode; }
    public void setToCode(String toCode) { this.toCode = toCode; }
    public String getToName() { return toName; }
    public void setToName(String toName) { this.toName = toName; }
    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }
    public Integer getConfidence() { return confidence; }
    public void setConfidence(Integer confidence) { this.confidence = confidence; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Boolean getLow() { return low; }
    public void setLow(Boolean low) { this.low = low; }
}
