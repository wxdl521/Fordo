package com.wenjin.service.rag;

import java.util.List;

/**
 * 检索端口（M8）。
 * <p>本里程碑实现为 {@link FulltextRetriever}（MySQL FULLTEXT ngram）。
 * 向量实现预留给竞赛后 v2：embedding 维度必须配置化校验（MoodFM 维度不匹配教训）。
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
