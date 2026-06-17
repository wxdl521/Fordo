package com.wenjin.ai;

import com.wenjin.dto.GraphImportRequest;

/**
 * 图谱 Excel 导入 AI 清洗客户端。
 * 将原始解析出的节点/边数据发送给 AI，修正格式、补充缺失字段、标准化关系类型。
 */
public interface GraphCleanAiClient {

    /**
     * AI 清洗 Excel 解析出的原始图谱数据。
     *
     * @param rawNodes 原始节点列表
     * @param rawEdges 原始边列表
     * @return 清洗后的 GraphImportRequest（含 nodes + edges）
     */
    GraphImportRequest clean(java.util.List<GraphImportRequest.NodeItem> rawNodes,
                             java.util.List<GraphImportRequest.EdgeItem> rawEdges);
}
