package com.wenjin.service;

import com.wenjin.dto.DiagnosticResultVO;
import com.wenjin.entity.AnswerRecord;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.AnswerRecordMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.impl.DiagnosticResultServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * DiagnosticResultServiceImpl 单测：Mockito 隔离 DB。
 * 因实现按固定顺序调用多个 mapper，统一用 LENIENT 宽松桩，避免无用桩告警。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiagnosticResultServiceImplTest {

    @Mock StudentMasteryMapper studentMasteryMapper;
    @Mock KgNodeMapper kgNodeMapper;
    @Mock KgEdgeMapper kgEdgeMapper;
    @Mock QuestionNodeMapper questionNodeMapper;
    @Mock QuestionOptionMapper questionOptionMapper;
    @Mock AnswerRecordMapper answerRecordMapper;
    @Mock QuestionMapper questionMapper;

    private static final Long S = 2L, C = 1L;

    private DiagnosticResultServiceImpl impl() {
        DiagnosticResultServiceImpl impl = new DiagnosticResultServiceImpl(
                studentMasteryMapper, kgNodeMapper, kgEdgeMapper,
                questionNodeMapper, questionOptionMapper, answerRecordMapper, questionMapper);
        ReflectionTestUtils.setField(impl, "masteredThreshold", 75.0);
        return impl;
    }

    private KgNode node(long id, String code, String name, boolean key) {
        KgNode n = new KgNode();
        n.setId(id); n.setCourseId(C); n.setNodeCode(code); n.setName(name);
        n.setChapter("ch"); n.setIsKey(key ? 1 : 0);
        return n;
    }

    private KgEdge prereq(long from, long to) {
        KgEdge e = new KgEdge();
        e.setCourseId(C); e.setFromNodeId(from); e.setToNodeId(to); e.setRelationType(1);
        return e;
    }

    private StudentMastery mastery(long nodeId, String score, int level) {
        StudentMastery m = new StudentMastery();
        m.setStudentId(S); m.setCourseId(C); m.setNodeId(nodeId);
        m.setMasteryScore(new BigDecimal(score)); m.setMasteryLevel(level);
        return m;
    }

    /** 默认把不关心的 mapper 桩成空，避免 NPE。 */
    private void stubEmptyAuxiliary() {
        when(questionNodeMapper.selectList(any())).thenReturn(List.of());
        when(questionOptionMapper.selectList(any())).thenReturn(List.of());
        when(answerRecordMapper.selectList(any())).thenReturn(List.of());
        when(questionMapper.selectList(any())).thenReturn(List.of());
        when(answerRecordMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    @DisplayName("A 链式薄弱：卡点=最下游(领域类图34)，回溯2跳根因=用例图(48)，非领域模型本身")
    void backtrackPicksUpstreamRoot() {
        // Z(业务流程,83 已掌握) →前置 A(用例图,48) →前置 B(业务实体,52) →前置 C(领域类图,34)
        KgNode z = node(1, "KT-Z", "业务流程分析", false);
        KgNode a = node(2, "KT-A", "用例图绘制", true);
        KgNode b = node(3, "KT-B", "业务实体识别", false);
        KgNode c = node(4, "KT-C", "领域类图绘制", true);
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(z, a, b, c));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1, 2), prereq(2, 3), prereq(3, 4)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(
                mastery(1, "83.00", 2), mastery(2, "48.00", 1),
                mastery(3, "52.00", 1), mastery(4, "34.00", 0)));
        stubEmptyAuxiliary();

        DiagnosticResultVO vo = impl().getResult(S, C);

        assertThat(vo.isHasWeakness()).isTrue();
        assertThat(vo.getStuckNode().getNodeCode()).isEqualTo("KT-C");
        assertThat(vo.getRootCause().getNodeCode()).isEqualTo("KT-A");
        assertThat(vo.getRootCause().isSelf()).isFalse();
        assertThat(vo.getChain()).extracting(DiagnosticResultVO.ChainNode::getNodeCode)
                .containsExactly("KT-A", "KT-B", "KT-C");
        assertThat(vo.getChain().get(0).getRole()).isEqualTo("root");
        assertThat(vo.getChain().get(2).getRole()).isEqualTo("stuck");
    }

    @Test
    @DisplayName("B 前置无作答数据：标待验证并推验证题，不判薄弱")
    void prereqWithoutDataBecomesPending() {
        KgNode p = node(1, "KT-P", "前置点", false);
        KgNode w = node(2, "KT-W", "薄弱点", false);
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(p, w));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1, 2)));
        // 只有 W 有行（薄弱），P 无行（无数据）
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(mastery(2, "50.00", 1)));
        when(questionOptionMapper.selectList(any())).thenReturn(List.of());
        when(answerRecordMapper.selectList(any())).thenReturn(List.of());
        when(answerRecordMapper.selectCount(any())).thenReturn(0L);
        // P 所辖题 → 推验证题
        QuestionNode qnP = new QuestionNode(); qnP.setQuestionId(901L); qnP.setNodeId(1L); qnP.setWeight(1);
        when(questionNodeMapper.selectList(any())).thenReturn(List.of(qnP));
        Question q = new Question(); q.setId(901L); q.setCourseId(C); q.setStatus(1);
        when(questionMapper.selectList(any())).thenReturn(List.of(q));

        DiagnosticResultVO vo = impl().getResult(S, C);

        assertThat(vo.getStuckNode().getNodeCode()).isEqualTo("KT-W");
        assertThat(vo.getRootCause().isSelf()).isTrue(); // 唯一薄弱前置无数据→无薄弱前置→根因=自身
        assertThat(vo.getPendingVerification()).extracting(DiagnosticResultVO.PendingNode::getNodeCode)
                .containsExactly("KT-P");
        assertThat(vo.getPendingVerification().get(0).getSuggestedQuestionIds()).contains(901L);
    }

    @Test
    @DisplayName("C 前置全已掌握、仅本点薄弱：根因=本知识点(self)")
    void allPrereqMasteredRootIsSelf() {
        KgNode p = node(1, "KT-P", "前置点", false);
        KgNode w = node(2, "KT-W", "卡点", false);
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(p, w));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1, 2)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(
                mastery(1, "85.00", 2), mastery(2, "40.00", 1)));
        stubEmptyAuxiliary();

        DiagnosticResultVO vo = impl().getResult(S, C);

        assertThat(vo.getStuckNode().getNodeCode()).isEqualTo("KT-W");
        assertThat(vo.getRootCause().getNodeCode()).isEqualTo("KT-W");
        assertThat(vo.getRootCause().isSelf()).isTrue();
        assertThat(vo.getChain()).hasSize(1);
        assertThat(vo.getChain().get(0).getRole()).isEqualTo("stuck");
    }

    @Test
    @DisplayName("D 多薄弱前置：嫌疑按 距离权重×缺口 降序取首作链，且 suspects 排序正确")
    void suspectsRankedByWeightedGap() {
        // C(卡点20) 前置 A(缺口大,30) 与 B(缺口小,70)，都 1 跳；A 排前
        KgNode a = node(1, "KT-A", "甲", false);
        KgNode b = node(2, "KT-B", "乙", false);
        KgNode c = node(3, "KT-C", "卡点", false);
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(a, b, c));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of(prereq(1, 3), prereq(2, 3)));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(
                mastery(1, "30.00", 0), mastery(2, "70.00", 1), mastery(3, "20.00", 0)));
        stubEmptyAuxiliary();

        DiagnosticResultVO vo = impl().getResult(S, C);

        assertThat(vo.getStuckNode().getNodeCode()).isEqualTo("KT-C");
        // 缺口：A=75-30=45，B=75-70=5；A 缺口更大→根因=A
        assertThat(vo.getRootCause().getNodeCode()).isEqualTo("KT-A");
        assertThat(vo.getSuspects()).extracting(DiagnosticResultVO.NodeRef::getNodeCode)
                .containsExactly("KT-A", "KT-B");
    }

    @Test
    @DisplayName("E 掌握度分布与覆盖：按三态计数，无行=未学")
    void distributionAndCoverage() {
        KgNode a = node(1, "KT-A", "甲", false); // 已掌握
        KgNode b = node(2, "KT-B", "乙", false); // 薄弱
        KgNode c = node(3, "KT-C", "丙", false); // 无行=未学
        when(kgNodeMapper.selectList(any())).thenReturn(List.of(a, b, c));
        when(kgEdgeMapper.selectList(any())).thenReturn(List.of());
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(
                mastery(1, "80.00", 2), mastery(2, "50.00", 1)));
        stubEmptyAuxiliary();

        DiagnosticResultVO vo = impl().getResult(S, C);

        assertThat(vo.getDistribution().getMastered()).isEqualTo(1);
        assertThat(vo.getDistribution().getWeak()).isEqualTo(1);
        assertThat(vo.getDistribution().getUnlearned()).isEqualTo(1);
        assertThat(vo.getCoverage().getCovered()).isEqualTo(2);
        assertThat(vo.getCoverage().getTotal()).isEqualTo(3);
    }
}
