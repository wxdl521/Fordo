package com.wenjin.ai;

import com.wenjin.entity.CompanionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 默认空蒸馏：不调用外部 LLM，返回空列表（仍推进 distilled watermark 的责任在 service 层决策）。
 * <p>用 name 条件避免与自身候选类型循环导致 bean 缺失。
 */
@Component
@ConditionalOnMissingBean(name = "openAiCompatibleMemoryDistillAiClient")
public class NoOpMemoryDistillAiClient implements MemoryDistillAiClient {

    @Override
    public List<DistilledItem> distill(List<CompanionMessage> messages, Set<String> nodeWhitelist) {
        return List.of();
    }
}
