package com.wenjin.eval.fixture;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.entity.MasterySnapshot;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.MasterySnapshotMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.MasteryService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Production-aligned mastery writer for eval profile ITs.
 * <p>Uses {@code insertEnsure} + update + {@link MasteryService#appendSnapshot} (not bare score-only UPDATE
 * without snapshot). Optional {@code updated_at} backdate so effective≈target via decay.
 */
public final class DbEvalMasteryPort implements EvalMasteryPort {

    private final StudentMasteryMapper masteryMapper;
    private final MasterySnapshotMapper snapshotMapper;
    private final MasteryService masteryService;
    private final MasteryDecayCalculator decayCalculator;
    private final JdbcTemplate jdbcTemplate;

    public DbEvalMasteryPort(StudentMasteryMapper masteryMapper,
                             MasterySnapshotMapper snapshotMapper,
                             MasteryService masteryService,
                             MasteryDecayCalculator decayCalculator,
                             JdbcTemplate jdbcTemplate) {
        this.masteryMapper = Objects.requireNonNull(masteryMapper, "masteryMapper");
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper");
        this.masteryService = Objects.requireNonNull(masteryService, "masteryService");
        this.decayCalculator = Objects.requireNonNull(decayCalculator, "decayCalculator");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public void writeMastery(long studentId, long courseId, long nodeId, double raw01, Double effective01) {
        double raw100 = clamp01(raw01) * 100.0;
        BigDecimal score = BigDecimal.valueOf(raw100).setScale(2, RoundingMode.HALF_UP);
        int level = masteryService.levelOf(score.doubleValue());
        double days = 0.0;
        if (effective01 != null) {
            double eff100 = clamp01(effective01) * 100.0;
            days = InMemoryEvalMasteryPort.daysForTargetEffective(score.doubleValue(), eff100);
            // Decay skips level=0; bump to weak so aged evidence can lower effective.
            if (days > 0 && eff100 < score.doubleValue() && level == 0) {
                level = 1;
            }
        }

        masteryMapper.insertEnsure(studentId, courseId, nodeId);
        StudentMastery row = masteryMapper.selectOne(new LambdaQueryWrapper<StudentMastery>()
                .eq(StudentMastery::getStudentId, studentId)
                .eq(StudentMastery::getCourseId, courseId)
                .eq(StudentMastery::getNodeId, nodeId)
                .last("LIMIT 1"));
        if (row == null) {
            throw new IllegalStateException("mastery row missing after insertEnsure nodeId=" + nodeId);
        }

        StudentMastery update = new StudentMastery();
        update.setId(row.getId());
        update.setStudentId(studentId);
        update.setCourseId(courseId);
        update.setNodeId(nodeId);
        update.setMasteryScore(score);
        update.setMasteryLevel(level);
        masteryMapper.updateById(update);

        masteryService.appendSnapshot(studentId, courseId, nodeId, score, level);

        if (days > 0) {
            LocalDateTime past = LocalDateTime.now().minusSeconds(Math.round(days * 86_400));
            jdbcTemplate.update(
                    "UPDATE student_mastery SET updated_at = ? WHERE id = ?",
                    past, row.getId());
        }
    }

    @Override
    public void clearMastery(long studentId, long courseId) {
        snapshotMapper.delete(new LambdaQueryWrapper<MasterySnapshot>()
                .eq(MasterySnapshot::getStudentId, studentId)
                .eq(MasterySnapshot::getCourseId, courseId));
        masteryMapper.delete(new LambdaQueryWrapper<StudentMastery>()
                .eq(StudentMastery::getStudentId, studentId)
                .eq(StudentMastery::getCourseId, courseId));
    }

    @Override
    public Double readRaw01(long studentId, long courseId, long nodeId) {
        StudentMastery row = find(studentId, courseId, nodeId);
        if (row == null || row.getMasteryScore() == null) {
            return null;
        }
        return row.getMasteryScore().doubleValue() / 100.0;
    }

    @Override
    public Double readEffective01(long studentId, long courseId, long nodeId) {
        StudentMastery row = find(studentId, courseId, nodeId);
        if (row == null || row.getMasteryScore() == null) {
            return null;
        }
        return decayCalculator.effectiveScoreOf(row) / 100.0;
    }

    private StudentMastery find(long studentId, long courseId, long nodeId) {
        List<StudentMastery> list = masteryMapper.selectList(new LambdaQueryWrapper<StudentMastery>()
                .eq(StudentMastery::getStudentId, studentId)
                .eq(StudentMastery::getCourseId, courseId)
                .eq(StudentMastery::getNodeId, nodeId)
                .last("LIMIT 1"));
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }
}
