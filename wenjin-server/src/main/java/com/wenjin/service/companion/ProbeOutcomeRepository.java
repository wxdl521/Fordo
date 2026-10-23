package com.wenjin.service.companion;

import java.time.Instant;
import java.util.Optional;

/**
 * 探针最近结果的持久化端口（V26 {@code probe_outcome}，每 学生×课程×节点 一行）。
 * 生产为 {@link MybatisProbeOutcomeRepository}，单测 / 无 DB 场景为 {@link InMemoryProbeOutcomeRepository}。
 */
public interface ProbeOutcomeRepository {

    Optional<ProbeOutcomeStore.NodeOutcome> find(Long studentId, Long courseId, String nodeCode);

    /** 该学生本课 {@code at} 最大的一条。 */
    Optional<ProbeOutcomeStore.NodeOutcome> findMostRecent(Long studentId, Long courseId);

    /** 整行写入（同键覆盖）。 */
    void save(Long studentId, Long courseId, ProbeOutcomeStore.NodeOutcome outcome);

    /** 删除 {@code at} 早于 cutoff 的行，返回删除行数。 */
    int deleteOlderThan(Instant cutoff);

    void deleteAll();

    long count();
}
