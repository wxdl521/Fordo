package com.wenjin.ai;

import com.wenjin.entity.CompanionMessage;

import java.util.List;
import java.util.Set;

/**
 * 会话片段 → 结构化记忆蒸馏（M7 MEMORY_DISTILL）。
 * <p>生产可接 LLM；测试用 Fake / 默认空实现，不强制出网。
 */
public interface MemoryDistillAiClient {

    /** 单条候选记忆。 */
    record DistilledItem(int kind, String content, String nodeCode) {}

    /**
     * 从消息窗口蒸馏记忆。
     *
     * @param messages       待蒸馏消息（升序；通常为 distilled_through 之后至 through）
     * @param nodeWhitelist  课程合法 node_code；不在集合内的 node 应丢弃或置空
     * @return 候选列表（调用方再夹紧 episodic≤3 / semantic≤2 / content≤200）
     */
    List<DistilledItem> distill(List<CompanionMessage> messages, Set<String> nodeWhitelist);
}
