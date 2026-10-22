package com.wenjin.service.rag;

/**
 * 某来源入库成功（READY）（R2）：NODE_REF 同步路径与 DOC_INGEST 作业路径都会发布。
 * 事务提交后：使课程向量索引失效，并在向量模式开时派发 RAG_EMBED 作业（失败只 warn，绝不影响入库）。
 *
 * @param courseId   课程
 * @param sourceId   来源
 * @param ownerId    作业属主（来源 created_by，须为教师 / 管理员）
 * @param minChunkId 本次入库的最小 chunkId（并入幂等键：重新入库产生新 chunk 时得到新作业）
 */
public record RagSourceIngestedEvent(long courseId, long sourceId, Long ownerId, long minChunkId) {
}
