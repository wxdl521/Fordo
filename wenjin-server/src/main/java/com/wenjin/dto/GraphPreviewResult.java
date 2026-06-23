package com.wenjin.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** AI 图谱预览 SVG 生成结果。 */
@Data
public class GraphPreviewResult {
    /** 生成的 SVG（valid=false 时可能是最后一版尝试，前端应改用兜底）。 */
    private String svg;
    /** 是否通过校验。 */
    private boolean valid;
    /** 违规项（valid=true 时为空）。 */
    private List<String> issues = new ArrayList<>();
    /** 实际调用 AI 的轮数。 */
    private int rounds;
    /** 来源：ai / ai-repaired / null（不合格或异常）。 */
    private String source;
}
