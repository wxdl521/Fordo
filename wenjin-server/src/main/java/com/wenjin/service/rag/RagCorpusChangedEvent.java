package com.wenjin.service.rag;

/**
 * 课程可检索语料变化（R2）：映射通过 / 驳回 / 批量通过、嵌入作业完成、来源归档等。
 * 由 {@link RagCorpusEventListener} 在事务提交后使该课程的向量索引失效。
 *
 * @param courseId 课程
 * @param reason   观测用原因（如 MAP_APPROVE、EMBED_DONE）
 */
public record RagCorpusChangedEvent(long courseId, String reason) {
}
