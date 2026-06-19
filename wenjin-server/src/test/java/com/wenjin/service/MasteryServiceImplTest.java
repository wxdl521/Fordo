package com.wenjin.service;

import com.wenjin.dto.GradedAnswer;
import com.wenjin.entity.MasterySnapshot;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.MasterySnapshotMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.impl.MasteryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MasteryServiceImpl 单元测试：Mockito 隔离数据库，覆盖
 *   冷启动对/错直给、低难度冷启动落未学、EWMA 对/错、难度因子、主次权重、
 *   同点多题累进（冷启动→EWMA）、多考点题扇出，并验证每次更新一条快照。
 */
@ExtendWith(MockitoExtension.class)
class MasteryServiceImplTest {

    @Mock QuestionMapper questionMapper;
    @Mock QuestionNodeMapper questionNodeMapper;
    @Mock StudentMasteryMapper studentMasteryMapper;
    @Mock MasterySnapshotMapper masterySnapshotMapper;

    private static final Long STUDENT = 2L;
    private static final Long COURSE = 1L;

    private MasteryServiceImpl impl() {
        MasteryServiceImpl impl = new MasteryServiceImpl(
                questionMapper, questionNodeMapper, studentMasteryMapper, masterySnapshotMapper);
        ReflectionTestUtils.setField(impl, "alpha", 0.3);
        ReflectionTestUtils.setField(impl, "masteredThreshold", 75.0);
        ReflectionTestUtils.setField(impl, "weakThreshold", 40.0);
        return impl;
    }

    private Question question(long id, int difficulty) {
        Question q = new Question();
        q.setId(id);
        q.setDifficulty(difficulty);
        return q;
    }

    private QuestionNode qnode(long questionId, long nodeId, int weight) {
        QuestionNode qn = new QuestionNode();
        qn.setId(questionId * 10 + nodeId);
        qn.setQuestionId(questionId);
        qn.setNodeId(nodeId);
        qn.setWeight(weight);
        return qn;
    }

    private StudentMastery existing(long nodeId, String score, int level) {
        StudentMastery sm = new StudentMastery();
        sm.setId(nodeId);
        sm.setStudentId(STUDENT);
        sm.setCourseId(COURSE);
        sm.setNodeId(nodeId);
        sm.setMasteryScore(new BigDecimal(score));
        sm.setMasteryLevel(level);
        return sm;
    }

    // ───────────────────── 冷启动 ─────────────────────

    @Test
    @DisplayName("A 冷启动答对(难度3,主点)：80.00 → 已掌握(2)，insert student_mastery + 1 快照")
    void coldStartCorrectBecomesMastered() {
        when(questionMapper.selectList(any())).thenReturn(List.of(question(1L, 3)));
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(qnode(1L, 100L, 1)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of());

        impl().applyAnswers(STUDENT, COURSE, List.of(new GradedAnswer(1L, true)));

        ArgumentCaptor<StudentMastery> smCap = ArgumentCaptor.forClass(StudentMastery.class);
        verify(studentMasteryMapper, times(1)).insert(smCap.capture());
        verify(studentMasteryMapper, never()).updateById(any(StudentMastery.class));
        StudentMastery saved = smCap.getValue();
        assertThat(saved.getNodeId()).isEqualTo(100L);
        assertThat(saved.getMasteryScore()).isEqualByComparingTo("80.00");
        assertThat(saved.getMasteryLevel()).isEqualTo(2);

        ArgumentCaptor<MasterySnapshot> snapCap = ArgumentCaptor.forClass(MasterySnapshot.class);
        verify(masterySnapshotMapper, times(1)).insert(snapCap.capture());
        MasterySnapshot snap = snapCap.getValue();
        assertThat(snap.getMasteryScore()).isEqualByComparingTo("80.00");
        assertThat(snap.getMasteryLevel()).isEqualTo(2);
        assertThat(snap.getSnapshotAt()).isNotNull();
    }

    @Test
    @DisplayName("B 冷启动答错(难度3,主点)：45.00 → 薄弱(1)")
    void coldStartWrongBecomesWeak() {
        when(questionMapper.selectList(any())).thenReturn(List.of(question(1L, 3)));
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(qnode(1L, 100L, 1)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of());

        impl().applyAnswers(STUDENT, COURSE, List.of(new GradedAnswer(1L, false)));

        ArgumentCaptor<StudentMastery> smCap = ArgumentCaptor.forClass(StudentMastery.class);
        verify(studentMasteryMapper, times(1)).insert(smCap.capture());
        assertThat(smCap.getValue().getMasteryScore()).isEqualByComparingTo("45.00");
        assertThat(smCap.getValue().getMasteryLevel()).isEqualTo(1);
    }

    @Test
    @DisplayName("C 冷启动答错(难度1)：35.00 → 未学(0)")
    void coldStartWrongLowDifficultyStaysUnlearned() {
        when(questionMapper.selectList(any())).thenReturn(List.of(question(1L, 1)));
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(qnode(1L, 100L, 1)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of());

        impl().applyAnswers(STUDENT, COURSE, List.of(new GradedAnswer(1L, false)));

        ArgumentCaptor<StudentMastery> smCap = ArgumentCaptor.forClass(StudentMastery.class);
        verify(studentMasteryMapper, times(1)).insert(smCap.capture());
        assertThat(smCap.getValue().getMasteryScore()).isEqualByComparingTo("35.00");
        assertThat(smCap.getValue().getMasteryLevel()).isEqualTo(0);
    }

    // ───────────────────── EWMA ─────────────────────

    @Test
    @DisplayName("D EWMA 答对(旧45,难度3,主点)：61.50 → 仍薄弱(1)，走 updateById 不 insert")
    void ewmaCorrectFromExisting() {
        when(questionMapper.selectList(any())).thenReturn(List.of(question(1L, 3)));
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(qnode(1L, 100L, 1)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(existing(100L, "45.00", 1)));

        impl().applyAnswers(STUDENT, COURSE, List.of(new GradedAnswer(1L, true)));

        ArgumentCaptor<StudentMastery> smCap = ArgumentCaptor.forClass(StudentMastery.class);
        verify(studentMasteryMapper, times(1)).updateById(smCap.capture());
        verify(studentMasteryMapper, never()).insert(any(StudentMastery.class));
        assertThat(smCap.getValue().getMasteryScore()).isEqualByComparingTo("61.50");
        assertThat(smCap.getValue().getMasteryLevel()).isEqualTo(1);
        verify(masterySnapshotMapper, times(1)).insert(any(MasterySnapshot.class));
    }

    @Test
    @DisplayName("E EWMA 答错(旧80,难度3,主点)：56.00 → 薄弱(1)")
    void ewmaWrongFromExisting() {
        when(questionMapper.selectList(any())).thenReturn(List.of(question(1L, 3)));
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(qnode(1L, 100L, 1)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(existing(100L, "80.00", 2)));

        impl().applyAnswers(STUDENT, COURSE, List.of(new GradedAnswer(1L, false)));

        ArgumentCaptor<StudentMastery> smCap = ArgumentCaptor.forClass(StudentMastery.class);
        verify(studentMasteryMapper, times(1)).updateById(smCap.capture());
        assertThat(smCap.getValue().getMasteryScore()).isEqualByComparingTo("56.00");
        assertThat(smCap.getValue().getMasteryLevel()).isEqualTo(1);
    }

    @Test
    @DisplayName("F 难度因子+封顶：难度5答对(旧50,主点) effAlpha 由 0.5 封顶到 0.4 → 70.00 仍薄弱(1)")
    void higherDifficultyMovesMore() {
        when(questionMapper.selectList(any())).thenReturn(List.of(question(1L, 5)));
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(qnode(1L, 100L, 1)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(existing(100L, "50.00", 1)));

        impl().applyAnswers(STUDENT, COURSE, List.of(new GradedAnswer(1L, true)));

        ArgumentCaptor<StudentMastery> smCap = ArgumentCaptor.forClass(StudentMastery.class);
        verify(studentMasteryMapper, times(1)).updateById(smCap.capture());
        // effAlpha = min(0.3 * 5/3 * 1.0, 0.4) = 0.4 → 50 + 0.4*(100-50) = 70.00
        assertThat(smCap.getValue().getMasteryScore()).isEqualByComparingTo("70.00");
        assertThat(smCap.getValue().getMasteryLevel()).isEqualTo(1);
    }

    @Test
    @DisplayName("G 主次权重：次点(weight=2,难度3,旧50)答对幅度减半 → 57.50（主点同条件应到65）")
    void subPointMovesHalf() {
        when(questionMapper.selectList(any())).thenReturn(List.of(question(1L, 3)));
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(qnode(1L, 100L, 2)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(existing(100L, "50.00", 1)));

        impl().applyAnswers(STUDENT, COURSE, List.of(new GradedAnswer(1L, true)));

        ArgumentCaptor<StudentMastery> smCap = ArgumentCaptor.forClass(StudentMastery.class);
        verify(studentMasteryMapper, times(1)).updateById(smCap.capture());
        assertThat(smCap.getValue().getMasteryScore()).isEqualByComparingTo("57.50");
    }

    // ───────────────────── 累进 / 扇出 ─────────────────────

    @Test
    @DisplayName("H 同点多题累进：先冷启动(80.00,insert) 再 EWMA(86.00,update)，2 条快照")
    void sameNodeMultiQuestionAccumulates() {
        when(questionMapper.selectList(any()))
                .thenReturn(List.of(question(1L, 3), question(2L, 3)));
        when(questionNodeMapper.selectList(any()))
                .thenReturn(List.of(qnode(1L, 100L, 1), qnode(2L, 100L, 1)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of());

        impl().applyAnswers(STUDENT, COURSE,
                List.of(new GradedAnswer(1L, true), new GradedAnswer(2L, true)));

        ArgumentCaptor<StudentMastery> insCap = ArgumentCaptor.forClass(StudentMastery.class);
        verify(studentMasteryMapper, times(1)).insert(insCap.capture());
        assertThat(insCap.getValue().getMasteryScore()).isEqualByComparingTo("80.00");

        ArgumentCaptor<StudentMastery> updCap = ArgumentCaptor.forClass(StudentMastery.class);
        verify(studentMasteryMapper, times(1)).updateById(updCap.capture());
        assertThat(updCap.getValue().getMasteryScore()).isEqualByComparingTo("86.00");

        verify(masterySnapshotMapper, times(2)).insert(any(MasterySnapshot.class));
    }

    @Test
    @DisplayName("I 多考点题扇出：一题含主点+次点 → 两点各 insert 一行 + 各一条快照")
    void multiNodeQuestionFansOut() {
        when(questionMapper.selectList(any())).thenReturn(List.of(question(1L, 3)));
        when(questionNodeMapper.selectList(any()))
                .thenReturn(List.of(qnode(1L, 100L, 1), qnode(1L, 200L, 2)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of());

        impl().applyAnswers(STUDENT, COURSE, List.of(new GradedAnswer(1L, true)));

        ArgumentCaptor<StudentMastery> insCap = ArgumentCaptor.forClass(StudentMastery.class);
        verify(studentMasteryMapper, times(2)).insert(insCap.capture());
        assertThat(insCap.getAllValues()).extracting(StudentMastery::getNodeId)
                .containsExactlyInAnyOrder(100L, 200L);
        verify(masterySnapshotMapper, times(2)).insert(any(MasterySnapshot.class));
    }
}
