package com.wenjin.service.memory;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.entity.CompanionConversation;
import com.wenjin.service.AiJobService;
import com.wenjin.support.AiJobKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 摘要推进后派发 MEMORY_DISTILL / SUMMARY_REFRESH（失败不影响主对话）。
 */
@Service
public class MemoryJobEnqueueService {

    private static final Logger log = LoggerFactory.getLogger(MemoryJobEnqueueService.class);

    private final AiJobService aiJobService;
    private final FeatureFlagsProperties featureFlags;

    public MemoryJobEnqueueService(AiJobService aiJobService, FeatureFlagsProperties featureFlags) {
        this.aiJobService = aiJobService;
        this.featureFlags = featureFlags;
    }

    /**
     * 在确定性 summary 守卫写成功后调用。
     * <ul>
     *   <li>{@code memory-enabled=false} → 不派发 MEMORY_DISTILL</li>
     *   <li>SUMMARY_REFRESH 仍可派发（仅受 ai-job-dispatch 总闸控制）</li>
     * </ul>
     */
    public void onSummaryUpdated(CompanionConversation conversation, Long throughId) {
        if (conversation == null || throughId == null
                || conversation.getId() == null
                || conversation.getStudentId() == null
                || conversation.getCourseId() == null) {
            return;
        }
        long convId = conversation.getId();
        long studentId = conversation.getStudentId();
        long courseId = conversation.getCourseId();

        if (featureFlags != null && featureFlags.isMemoryEnabled()) {
            try {
                aiJobService.enqueueInternal(
                        courseId,
                        studentId,
                        AiJobKind.MEMORY_DISTILL,
                        MemoryDistillService.requestKey(convId, throughId),
                        MemoryDistillService.requestBody(convId, throughId, studentId, courseId));
            } catch (Exception e) {
                log.warn("enqueue MEMORY_DISTILL failed: convId={} throughId={}: {}",
                        convId, throughId, e.getMessage());
            }
        }

        try {
            aiJobService.enqueueInternal(
                    courseId,
                    studentId,
                    AiJobKind.SUMMARY_REFRESH,
                    SummaryRefreshService.requestKey(convId, throughId),
                    SummaryRefreshService.requestBody(convId, throughId, studentId, courseId));
        } catch (Exception e) {
            log.warn("enqueue SUMMARY_REFRESH failed: convId={} throughId={}: {}",
                    convId, throughId, e.getMessage());
        }
    }
}
