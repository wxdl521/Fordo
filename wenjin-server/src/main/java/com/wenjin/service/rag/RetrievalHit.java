package com.wenjin.service.rag;

/**
 * 单条检索命中（M8）。
 */
public record RetrievalHit(
        long chunkId,
        String content,
        String sourceTitle,
        String metaJson,
        double score
) {
}
