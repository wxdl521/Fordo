package com.wenjin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 图谱导入成功后的结果摘要。
 */
@Data
@AllArgsConstructor
public class GraphImportResult {

    /** 落库后的课程主键，前端查询图谱用 GET /api/graph/{courseId} */
    private Long courseId;

    /** 课程业务编码 */
    private String courseCode;

    /** 课程名称 */
    private String courseName;

    /** 入库节点数 */
    private int nodeCount;

    /** 入库边数 */
    private int edgeCount;
}
