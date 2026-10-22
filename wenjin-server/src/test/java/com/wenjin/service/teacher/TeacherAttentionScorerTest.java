package com.wenjin.service.teacher;

import com.wenjin.service.teacher.TeacherAttentionScorer.Facts;
import com.wenjin.service.teacher.TeacherAttentionScorer.Scored;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TeacherAttentionScorerTest {

    private static final double MASTERED = 75;
    private static final double WEAK = 40;

    private static Facts facts(Double avg, double drop, Integer days, int wrong7, int backlog) {
        return new Facts(1L, "张三", avg, drop, "循环", days, "2026-10-20", wrong7, backlog);
    }

    @Test
    void healthyStudentIsNotFlagged() {
        Scored s = TeacherAttentionScorer.score(facts(80.0, 0.02, 1, 0, 0), MASTERED, WEAK);
        assertThat(s.flagged()).isFalse();
        assertThat(s.score()).isZero();
    }

    @Test
    void lowMasteryBelowWeakLineScoresThirtyToForty() {
        Scored s = TeacherAttentionScorer.score(facts(20.0, 0, 1, 0, 0), MASTERED, WEAK);
        assertThat(s.codes()).containsExactly("LOW_MASTERY");
        assertThat(s.score()).isEqualTo(35);
        assertThat(s.reasons().get(0)).contains("20.0").contains("低于薄弱线");
    }

    @Test
    void midBandScoresProportionallyAndAboveCutoffIsNotFlagged() {
        Scored mid = TeacherAttentionScorer.score(facts(50.0, 0, 1, 0, 0), MASTERED, WEAK);
        assertThat(mid.codes()).containsExactly("LOW_MASTERY");
        assertThat(mid.score()).isEqualTo(21); // 30 * 25/35
        assertThat(TeacherAttentionScorer.score(facts(60.0, 0, 1, 0, 0), MASTERED, WEAK).flagged()).isFalse();
    }

    @Test
    void undiagnosedStudentGetsUndiagnosedReason() {
        Scored s = TeacherAttentionScorer.score(facts(null, 0, null, 0, 0), MASTERED, WEAK);
        assertThat(s.codes()).containsExactly("UNDIAGNOSED", "NO_PRACTICE");
        assertThat(s.score()).isEqualTo(50);
        assertThat(s.reasons()).contains("从未作答");
    }

    @Test
    void dropOnlyCountsAboveThreshold() {
        assertThat(TeacherAttentionScorer.score(facts(80.0, 0.15, 1, 0, 0), MASTERED, WEAK).flagged()).isFalse();
        Scored s = TeacherAttentionScorer.score(facts(80.0, 0.25, 1, 0, 0), MASTERED, WEAK);
        assertThat(s.codes()).containsExactly("DROP");
        assertThat(s.score()).isEqualTo(25);
        assertThat(s.reasons().get(0)).contains("「循环」").contains("25.0");
    }

    @Test
    void noPracticeNeedsFourteenDays() {
        assertThat(TeacherAttentionScorer.score(facts(80.0, 0, 13, 0, 0), MASTERED, WEAK).flagged()).isFalse();
        Scored s = TeacherAttentionScorer.score(facts(80.0, 0, 20, 0, 0), MASTERED, WEAK);
        assertThat(s.codes()).containsExactly("NO_PRACTICE");
        assertThat(s.score()).isEqualTo(23);
    }

    @Test
    void manyWrongAndBacklogThresholds() {
        assertThat(TeacherAttentionScorer.score(facts(80.0, 0, 1, 4, 2), MASTERED, WEAK).flagged()).isFalse();
        Scored s = TeacherAttentionScorer.score(facts(80.0, 0, 1, 8, 5), MASTERED, WEAK);
        assertThat(s.codes()).containsExactly("MANY_WRONG", "REVIEW_BACKLOG");
        assertThat(s.score()).isEqualTo(13 + 12);
    }

    @Test
    void rankingIsScoreDescThenLowerAvgThenId() {
        Scored a = TeacherAttentionScorer.score(new Facts(3L, "a", 30.0, 0, null, 1, null, 0, 0), MASTERED, WEAK);
        Scored b = TeacherAttentionScorer.score(new Facts(2L, "b", 20.0, 0, null, 1, null, 0, 0), MASTERED, WEAK);
        Scored c = TeacherAttentionScorer.score(new Facts(1L, "c", 20.0, 0, null, 1, null, 0, 0), MASTERED, WEAK);
        Scored top = TeacherAttentionScorer.score(new Facts(9L, "d", 70.0, 0.4, "x", 30, null, 9, 4), MASTERED, WEAK);
        List<Scored> sorted = java.util.stream.Stream.of(a, b, c, top).sorted(TeacherAttentionScorer.RANKING).toList();
        assertThat(sorted).extracting(x -> x.facts().studentId()).containsExactly(9L, 1L, 2L, 3L);
    }
}
