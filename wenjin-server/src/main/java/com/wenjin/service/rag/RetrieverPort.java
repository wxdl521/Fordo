package com.wenjin.service.rag;

import java.util.List;

/**
 * 检索端口（M8）。
 * <p>生效实现为 {@link HybridRetriever}（{@code @Primary}，R2：全文 + 向量 RRF，向量不可用时逐字节回退
 * {@link FulltextRetriever}）。向量维度经 modelId（如 {@code embedding-3@1024}）隔离并在客户端校验。
 */
public interface RetrieverPort {

    /**
     * @param courseId  课程
     * @param nodeCodes 节点圈定；空/null 表示全课程（调用方决定是否放宽）
     * @param query     查询串
     * @param topK      返回条数上限
     */
    List<RetrievalHit> retrieve(long courseId, List<String> nodeCodes, String query, int topK);
}
