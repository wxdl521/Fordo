package com.wenjin.service.companion;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * 探针会话的持久化端口（V26 {@code probe_session}）。{@link ProbeSessionStore} 之下的唯一状态来源：
 * 生产为 {@link MybatisProbeSessionRepository}，单测 / 无 DB 场景为 {@link InMemoryProbeSessionRepository}。
 */
public interface ProbeSessionRepository {

    void insert(ProbeSessionStore.ProbeSession session);

    Optional<ProbeSessionStore.ProbeSession> findById(String sessionId);

    /** 比较并设置：仅当尚未提交时写入并返回 true；已提交（含并发落败）返回 false，不改原判分。 */
    boolean markSubmitted(String sessionId, Instant at, boolean correct);

    /** 该学生×课程×节点下所有已发过的题库题 id（含未提交与已提交；不看 TTL，直到被清理）。 */
    Set<Long> usedBankQuestionIds(Long studentId, Long courseId, String nodeCode);

    /** 删除创建时间早于 cutoff 的会话，返回删除行数。 */
    int deleteCreatedBefore(Instant cutoff);

    void deleteAll();

    long count();
}
