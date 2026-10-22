package com.wenjin.ai;

import java.util.List;

/**
 * 文本嵌入客户端（RAG 向量检索）。实现必须返回与入参等长、同序的 L2 归一化或至少定长向量。
 */
public interface EmbeddingClient {

    /** 批量嵌入；返回列表与 texts 等长同序，每个向量长度等于当前维度。失败抛 BusinessException(AI_ERROR)。 */
    List<float[]> embed(List<String> texts);

    /** 模型标识（含维度），如 {@code embedding-3@1024}；向量表按它隔离不同模型/维度。 */
    String modelId();

    /** 当前是否可用（已启用且具备调用条件）。 */
    boolean available();
}
