package com.wenjin.service.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * R2 语料事件处理：一律在事务提交后执行（无事务时立即执行），失败只 warn。
 * <ul>
 *   <li>{@link RagCorpusChangedEvent} → 课程向量索引失效；</li>
 *   <li>{@link RagSourceIngestedEvent} → 索引失效 + 向量模式开时派发 RAG_EMBED（新事务）。</li>
 * </ul>
 * 提交后再派发，保证作业 worker 一定能读到已提交的 chunk。
 */
@Component
public class RagCorpusEventListener {

    private static final Logger log = LoggerFactory.getLogger(RagCorpusEventListener.class);

    private final VectorIndexCache indexCache;
    private final RagEmbedService embedService;

    public RagCorpusEventListener(VectorIndexCache indexCache, RagEmbedService embedService) {
        this.indexCache = indexCache;
        this.embedService = embedService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCorpusChanged(RagCorpusChangedEvent event) {
        try {
            indexCache.invalidateCourse(event.courseId());
        } catch (RuntimeException e) {
            log.warn("rag index invalidate failed courseId={}: {}", event.courseId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSourceIngested(RagSourceIngestedEvent event) {
        try {
            indexCache.invalidateCourse(event.courseId());
        } catch (RuntimeException e) {
            log.warn("rag index invalidate failed courseId={}: {}", event.courseId(), e.getMessage());
        }
        try {
            embedService.dispatchAfterIngest(event);
        } catch (RuntimeException e) {
            log.warn("RAG_EMBED dispatch failed sourceId={}: {}", event.sourceId(), e.getMessage());
        }
    }
}
