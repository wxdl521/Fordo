package com.wenjin.service.companion;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenjin.entity.ProbeOutcomeRow;
import com.wenjin.mapper.ProbeOutcomeMapper;
import com.wenjin.service.companion.StrategySwitcher.Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** MySQL 实现（V26 {@code probe_outcome}）。 */
@Repository
public class MybatisProbeOutcomeRepository implements ProbeOutcomeRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisProbeOutcomeRepository.class);

    private final ProbeOutcomeMapper mapper;

    public MybatisProbeOutcomeRepository(ProbeOutcomeMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Optional<ProbeOutcomeStore.NodeOutcome> find(Long studentId, Long courseId, String nodeCode) {
        ProbeOutcomeRow row = mapper.selectOne(new QueryWrapper<ProbeOutcomeRow>()
                .eq("student_id", studentId)
                .eq("course_id", courseId)
                .eq("node_code", nodeCode));
        return Optional.ofNullable(row).map(MybatisProbeOutcomeRepository::toOutcome);
    }

    @Override
    public Optional<ProbeOutcomeStore.NodeOutcome> findMostRecent(Long studentId, Long courseId) {
        ProbeOutcomeRow row = mapper.selectList(new QueryWrapper<ProbeOutcomeRow>()
                        .eq("student_id", studentId)
                        .eq("course_id", courseId)
                        .orderByDesc("updated_at")
                        .orderByDesc("id")
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        return Optional.ofNullable(row).map(MybatisProbeOutcomeRepository::toOutcome);
    }

    @Override
    public void save(Long studentId, Long courseId, ProbeOutcomeStore.NodeOutcome o) {
        ProbeOutcomeRow row = new ProbeOutcomeRow();
        row.setStudentId(studentId);
        row.setCourseId(courseId);
        row.setNodeCode(o.nodeCode());
        row.setCorrect(o.correct());
        row.setStrategy(o.strategyUsed() == null ? null : o.strategyUsed().name());
        row.setActiveStrategy(o.activeStrategy() == null ? null : o.activeStrategy().name());
        row.setTriedStrategies(o.tried().isEmpty() ? null
                : o.tried().stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        row.setSwitchConsumed(o.switchConsumed());
        row.setOutcomeRecorded(o.outcomeRecorded());
        row.setUpdatedAt(MybatisProbeSessionRepository.toLocal(o.at()));
        mapper.upsert(row);
    }

    @Override
    public int deleteOlderThan(Instant cutoff) {
        return mapper.deleteUpdatedBefore(MybatisProbeSessionRepository.toLocal(cutoff));
    }

    @Override
    public void deleteAll() {
        mapper.delete(new QueryWrapper<>());
    }

    @Override
    public long count() {
        Long n = mapper.selectCount(new QueryWrapper<>());
        return n == null ? 0 : n;
    }

    private static ProbeOutcomeStore.NodeOutcome toOutcome(ProbeOutcomeRow r) {
        return new ProbeOutcomeStore.NodeOutcome(
                r.getNodeCode(),
                Boolean.TRUE.equals(r.getCorrect()),
                MybatisProbeSessionRepository.toInstant(r.getUpdatedAt()),
                parse(r.getStrategy()),
                parse(r.getActiveStrategy()),
                parseSet(r.getTriedStrategies()),
                Boolean.TRUE.equals(r.getSwitchConsumed()),
                Boolean.TRUE.equals(r.getOutcomeRecorded()));
    }

    private static Strategy parse(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        try {
            return Strategy.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            log.warn("unknown strategy '{}' in probe_outcome, ignored", name);
            return null;
        }
    }

    private static Set<Strategy> parseSet(String csv) {
        EnumSet<Strategy> set = EnumSet.noneOf(Strategy.class);
        if (StringUtils.hasText(csv)) {
            for (String part : csv.split(",")) {
                Strategy s = parse(part);
                if (s != null) {
                    set.add(s);
                }
            }
        }
        return set;
    }
}
