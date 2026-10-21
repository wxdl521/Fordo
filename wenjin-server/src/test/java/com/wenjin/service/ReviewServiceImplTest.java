package com.wenjin.service;

import com.wenjin.dto.ReviewTodayVO;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.companion.ReviewScheduleStore;
import com.wenjin.service.impl.ReviewServiceImpl;
import com.wenjin.support.QuestionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewServiceImpl 单测：入队判据、urgency 排序稳定性、daily-size 截断、0 题节点、availableQuestions。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewServiceImplTest {

    @Mock StudentMasteryMapper studentMasteryMapper;
    @Mock KgNodeMapper kgNodeMapper;
    @Mock KgEdgeMapper kgEdgeMapper;
    @Mock QuestionNodeMapper questionNodeMapper;
    @Mock QuestionMapper questionMapper;

    private static final Long S = 2L, C = 1L;
    private static final double TAU_DAYS = 30.0;
    private static final double DECAY_FLOOR = 25.0;
    private static final double MASTERED = 75.0;
    private static final double WEAK = 40.0;
    private static final int DAILY_SIZE = 5;

    @BeforeEach
    void stubAvailDefaults() {
        when(questionNodeMapper.selectList(any())).thenReturn(List.of());
        when(questionMapper.selectList(any())).thenReturn(List.of());
    }

    private ReviewServiceImpl impl() {
        return implWithDecay(true, DAILY_SIZE);
    }

    /** 小工厂：构建真实 MasteryDecayCalculator 并注入服务，避免在每个用例重复 setField。 */
    private MasteryDecayCalculator calculator(boolean enabled) {
        MasteryDecayCalculator calc = new MasteryDecayCalculator();
        ReflectionTestUtils.setField(calc, "enabled", enabled);
        ReflectionTestUtils.setField(calc, "tauDays", TAU_DAYS);
        ReflectionTestUtils.setField(calc, "floor", DECAY_FLOOR);
        ReflectionTestUtils.setField(calc, "masteredThreshold", MASTERED);
        ReflectionTestUtils.setField(calc, "weakThreshold", WEAK);
        return calc;
    }

    private ReviewServiceImpl implWithDecay(boolean decayEnabled, int dailySize) {
        ReviewServiceImpl impl = new ReviewServiceImpl(
                studentMasteryMapper, kgNodeMapper, kgEdgeMapper,
                questionNodeMapper, questionMapper, calculator(decayEnabled),
                new ReviewScheduleStore());
        ReflectionTestUtils.setField(impl, "dailySize", dailySize);
        return impl;
    }

    private KgNode node(long id, String code, String name, int isKey) {
        KgNode n = new KgNode();
        n.setId(id);
        n.setCourseId(C);
        n.setNodeCode(code);
        n.setName(name);
        n.setChapter("ch" + id);
        n.setIsKey(isKey);
        return n;
    }

    private KgEdge prereq(long from, long to) {
        KgEdge e = new KgEdge();
        e.setCourseId(C);
        e.setFromNodeId(from);
        e.setToNodeId(to);
        e.setRelationType(1);
        return e;
    }

    private StudentMastery mastery(long nodeId, String score, int level, LocalDateTime updatedAt) {
        StudentMastery m = new StudentMastery();
        m.setStudentId(S);
        m.setCourseId(C);
        m.setNodeId(nodeId);
        m.setMasteryScore(new BigDecimal(score));
        m.setMasteryLevel(level);
        m.setUpdatedAt(updatedAt);
        return m;
    }

    private QuestionNode link(long questionId, long nodeId) {
        QuestionNode qn = new QuestionNode();
        qn.setQuestionId(questionId);
        qn.setNodeId(nodeId);
        qn.setWeight(1);
        return qn;
    }

    private Question approvedQuestion(long id) {
        Question q = new Question();
        q.setId(id);
        q.setStatus(QuestionStatus.APPROVED);
        return q;
    }

    // ═══════════════════════════════════════════════════════════════
    // 入队判据：raw ≥ mastered 且 effective < mastered
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("R1 入队：raw=88 且 40 天无证据 → effective<75，入选")
    void decayingMasteredNode_enqueued() {
        LocalDateTime stale = LocalDateTime.now().minusDays(40);
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(1, "A", "甲", 0)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());
        when(studentMasteryMapper.selectList(any())).thenReturn(
                List.of(mastery(1, "88.00", 2, stale)));

        double effective = MasteryDecayCalculator.effectiveScore(88.0, 40.0, TAU_DAYS, DECAY_FLOOR, 2);
        assertThat(effective).isLessThan(MASTERED);

        ReviewTodayVO vo = impl().getToday(S, C);

        assertThat(vo.getTotalDecayingCount()).isEqualTo(1);
        assertThat(vo.getItems()).hasSize(1);
        ReviewTodayVO.ReviewItemVO item = vo.getItems().get(0);
        assertThat(item.getNodeCode()).isEqualTo("A");
        assertThat(item.getRawScore()).isEqualTo(88.0);
        assertThat(item.getEffectiveScore()).isCloseTo(effective, org.assertj.core.data.Offset.offset(0.5));
        assertThat(item.getEffectiveLevel()).isEqualTo(1);
    }

    @Test
    @DisplayName("R2 不入队：raw=60 从未掌握，即使衰减也不入选")
    void neverMasteredNode_excluded() {
        LocalDateTime stale = LocalDateTime.now().minusDays(40);
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(1, "W", "薄弱", 0)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());
        when(studentMasteryMapper.selectList(any())).thenReturn(
                List.of(mastery(1, "60.00", 1, stale)));

        ReviewTodayVO vo = impl().getToday(S, C);

        assertThat(vo.getTotalDecayingCount()).isEqualTo(0);
        assertThat(vo.getItems()).isEmpty();
    }

    @Test
    @DisplayName("R3 不入队：raw=88 但证据新鲜 → effective≥75")
    void freshEvidenceMasteredNode_excluded() {
        LocalDateTime fresh = LocalDateTime.now();
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(1, "F", "新鲜", 0)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());
        when(studentMasteryMapper.selectList(any())).thenReturn(
                List.of(mastery(1, "88.00", 2, fresh)));

        ReviewTodayVO vo = impl().getToday(S, C);

        assertThat(vo.getTotalDecayingCount()).isEqualTo(0);
        assertThat(vo.getItems()).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // urgency 排序稳定性
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("R4 urgency 降序：衰减幅度大者优先；平手按 nodeId 升序稳定")
    void urgencySort_descWithStableTiebreak() {
        LocalDateTime stale = LocalDateTime.now().minusDays(40);
        // A: raw=90, isKey=0, outDegree=0
        // B: raw=88, isKey=1, outDegree=2 → 更大加权
        // C: raw=88, isKey=0, outDegree=0 → 与 D 同 urgency，nodeId 小者先
        // D: raw=88, isKey=0, outDegree=0
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(
                node(1, "A", "甲", 0),
                node(2, "B", "乙", 1),
                node(3, "C", "丙", 0),
                node(4, "D", "丁", 0)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(
                prereq(2, 10), prereq(2, 11))); // B 出度=2
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(
                mastery(1, "90.00", 2, stale),
                mastery(2, "88.00", 2, stale),
                mastery(3, "88.00", 2, stale),
                mastery(4, "88.00", 2, stale)));

        ReviewTodayVO vo = impl().getToday(S, C);

        assertThat(vo.getItems()).extracting(ReviewTodayVO.ReviewItemVO::getNodeCode)
                .containsExactly("B", "A", "C", "D");

        // 平手节点 C/D：urgency 相同，nodeId 3 < 4
        ReviewTodayVO.ReviewItemVO c = vo.getItems().get(2);
        ReviewTodayVO.ReviewItemVO d = vo.getItems().get(3);
        assertThat(c.getUrgency()).isEqualTo(d.getUrgency());
        assertThat(c.getNodeId()).isLessThan(d.getNodeId());
    }

    // ═══════════════════════════════════════════════════════════════
    // daily-size 截断
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("R5 daily-size=5：7 个衰减节点 → items=5，totalDecayingCount=7")
    void dailySize_truncatesItemsKeepsFullCount() {
        LocalDateTime stale = LocalDateTime.now().minusDays(40);
        List<KgNode> nodes = new java.util.ArrayList<>();
        List<StudentMastery> masteries = new java.util.ArrayList<>();
        for (long i = 1; i <= 7; i++) {
            nodes.add(node(i, "N" + i, "节点" + i, 0));
            masteries.add(mastery(i, "88.00", 2, stale));
        }
        when(kgNodeMapper.selectList(any())).thenReturn(nodes);
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());
        when(studentMasteryMapper.selectList(any())).thenReturn(masteries);

        ReviewTodayVO vo = implWithDecay(true, 5).getToday(S, C);

        assertThat(vo.getTotalDecayingCount()).isEqualTo(7);
        assertThat(vo.getItems()).hasSize(5);
    }

    // ═══════════════════════════════════════════════════════════════
    // 0 题节点仍列出
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("R6 0 题节点仍入队，availableQuestions=0")
    void zeroQuestionNode_stillListedWithZeroCount() {
        LocalDateTime stale = LocalDateTime.now().minusDays(40);
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(1, "Z", "无题", 0)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());
        when(studentMasteryMapper.selectList(any())).thenReturn(
                List.of(mastery(1, "88.00", 2, stale)));
        when(questionNodeMapper.selectList(any())).thenReturn(List.of());

        ReviewTodayVO vo = impl().getToday(S, C);

        assertThat(vo.getItems()).hasSize(1);
        assertThat(vo.getItems().get(0).getAvailableQuestions()).isEqualTo(0);
        verify(questionMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("R7 availableQuestions 只计 APPROVED 关联题")
    void availableQuestions_countsApprovedOnly() {
        LocalDateTime stale = LocalDateTime.now().minusDays(40);
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(node(1, "Q", "有题", 0)));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());
        when(studentMasteryMapper.selectList(any())).thenReturn(
                List.of(mastery(1, "88.00", 2, stale)));
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(
                link(10L, 1L), link(20L, 1L), link(30L, 1L)));
        when(questionMapper.selectList(any())).thenReturn(List.of(
                approvedQuestion(10L), approvedQuestion(20L)));

        ReviewTodayVO vo = impl().getToday(S, C);

        assertThat(vo.getItems().get(0).getAvailableQuestions()).isEqualTo(2);
    }
}