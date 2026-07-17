package com.wenjin.service;

import com.wenjin.dto.DashboardVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.TeacherDashboardMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.service.impl.TeacherDashboardServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherDashboardServiceImplTest {

    @Mock TeacherDashboardMapper dashboardMapper;
    @Mock KgNodeMapper nodeMapper;
    @Mock CourseAccessService courseAccessService;

    private TeacherDashboardServiceImpl service;

    private static final double TAU_DAYS = 30.0;
    private static final double DECAY_FLOOR = 25.0;

    private MasteryDecayCalculator calculator(boolean enabled) {
        MasteryDecayCalculator calc = new MasteryDecayCalculator();
        ReflectionTestUtils.setField(calc, "enabled", enabled);
        ReflectionTestUtils.setField(calc, "tauDays", TAU_DAYS);
        ReflectionTestUtils.setField(calc, "floor", DECAY_FLOOR);
        ReflectionTestUtils.setField(calc, "masteredThreshold", 75.0);
        ReflectionTestUtils.setField(calc, "weakThreshold", 40.0);
        return calc;
    }

    @BeforeEach
    void setDecayConfig() {
        lenient().doNothing().when(courseAccessService).assertManageCourse(anyLong());
        service = new TeacherDashboardServiceImpl(dashboardMapper, nodeMapper, calculator(true),
                courseAccessService);
    }

    private StudentMastery sm(long stu, long node, String score, int lvl) {
        return sm(stu, node, score, lvl, LocalDateTime.now());
    }

    private StudentMastery sm(long stu, long node, String score, int lvl, LocalDateTime updatedAt) {
        StudentMastery m = new StudentMastery();
        m.setStudentId(stu);
        m.setCourseId(1L);
        m.setNodeId(node);
        m.setMasteryScore(new BigDecimal(score));
        m.setMasteryLevel(lvl);
        m.setUpdatedAt(updatedAt);
        return m;
    }

    private KgNode kn(long id, String code, String name, String chap) {
        KgNode n = new KgNode();
        n.setId(id);
        n.setCourseId(1L);
        n.setNodeCode(code);
        n.setName(name);
        n.setChapter(chap);
        return n;
    }

    @Test
    @DisplayName("ownership assert 必须先于任何读模型查询")
    void assertManageCourseRunsFirst() {
        when(dashboardMapper.countEnrolled(1L)).thenReturn(0);
        when(dashboardMapper.countDiagnosed(1L)).thenReturn(0);
        when(dashboardMapper.selectEnrolledMastery(1L)).thenReturn(Collections.emptyList());
        when(nodeMapper.selectList(any())).thenReturn(Collections.emptyList());

        service.dashboard(1L);

        InOrder order = inOrder(courseAccessService, dashboardMapper);
        order.verify(courseAccessService).assertManageCourse(1L);
        order.verify(dashboardMapper).countEnrolled(1L);
    }

    @Test
    void aggregatesPerNodeAndRanksWeak() {
        // roster=3；节点 100：甲掌握/乙薄弱/丙薄弱；节点 200：甲薄弱/乙丙无 mastery
        when(dashboardMapper.countEnrolled(1L)).thenReturn(3);
        when(dashboardMapper.countDiagnosed(1L)).thenReturn(2);
        when(dashboardMapper.selectEnrolledMastery(1L)).thenReturn(Arrays.asList(
                sm(11, 100, "88", 2), sm(12, 100, "60", 1), sm(13, 100, "55", 1),
                sm(11, 200, "55", 1)
        ));
        when(nodeMapper.selectList(any())).thenReturn(Arrays.asList(
                kn(100, "KT01", "软工概述", "概述"), kn(200, "KT20", "类设计转代码", "对象设计")
        ));

        DashboardVO vo = service.dashboard(1L);

        assertThat(vo.getSummary().getTotalStudents()).isEqualTo(3);
        assertThat(vo.getSummary().getDiagnosedStudents()).isEqualTo(2);
        assertThat(vo.getSummary().getNodeCount()).isEqualTo(2);
        // mastered cells = 1 (only KT01/甲); denom = 3*2=6 → 1/6 ≈ 0.167
        assertThat(vo.getSummary().getClassAvgRate()).isCloseTo(1.0 / 6, within(0.001));

        DashboardVO.NodeStat kt01 = vo.getNodes().stream()
                .filter(n -> n.getNodeCode().equals("KT01")).findFirst().orElseThrow();
        assertThat(kt01.getMastered()).isEqualTo(1);
        assertThat(kt01.getWeak()).isEqualTo(2);
        assertThat(kt01.getUndiagnosed()).isEqualTo(0);
        assertThat(kt01.getRate()).isEqualTo(1.0 / 3);

        DashboardVO.NodeStat kt20 = vo.getNodes().stream()
                .filter(n -> n.getNodeCode().equals("KT20")).findFirst().orElseThrow();
        assertThat(kt20.getWeak()).isEqualTo(1);
        assertThat(kt20.getUndiagnosed()).isEqualTo(2); // roster 3 − 1 with mastery

        assertThat(vo.getWeakRanking().get(0).getNodeCode()).isEqualTo("KT01");
        assertThat(vo.getWeakRanking().get(0).getMasteredPct()).isEqualTo(1 * 100 / 3);
        assertThat(vo.getWeakRanking().get(0).getWeakPct()).isEqualTo(2 * 100 / 3);
    }

    @Test
    @DisplayName("ghost mastery（未在 roster）不得抬高 totalStudents / undiagnosed 分母")
    void excludesGhostMasteryFromRoster() {
        // 选课仅 2 人；mapper 已排除 ghost，只返回选课学生 mastery
        when(dashboardMapper.countEnrolled(1L)).thenReturn(2);
        when(dashboardMapper.countDiagnosed(1L)).thenReturn(1);
        when(dashboardMapper.selectEnrolledMastery(1L)).thenReturn(Arrays.asList(
                sm(11, 100, "88", 2), sm(12, 100, "60", 1)
        ));
        when(nodeMapper.selectList(any())).thenReturn(Arrays.asList(
                kn(100, "KT01", "软工概述", "概述")
        ));

        DashboardVO vo = service.dashboard(1L);

        assertThat(vo.getSummary().getTotalStudents()).isEqualTo(2);
        DashboardVO.NodeStat kt01 = vo.getNodes().get(0);
        assertThat(kt01.getMastered() + kt01.getWeak()).isEqualTo(2);
        assertThat(kt01.getUndiagnosed()).isEqualTo(0);
        // classAvgRate = 1 mastered / (2*1) = 0.5
        assertThat(vo.getSummary().getClassAvgRate()).isCloseTo(0.5, within(0.001));
    }

    @Test
    void emptyWhenNoEnrollment() {
        when(dashboardMapper.countEnrolled(1L)).thenReturn(0);
        when(dashboardMapper.countDiagnosed(1L)).thenReturn(0);
        when(dashboardMapper.selectEnrolledMastery(1L)).thenReturn(Collections.emptyList());
        when(nodeMapper.selectList(any())).thenReturn(Arrays.asList(kn(100, "KT01", "软工概述", "概述")));

        DashboardVO vo = service.dashboard(1L);

        assertThat(vo.getSummary().getTotalStudents()).isEqualTo(0);
        assertThat(vo.getSummary().getDiagnosedStudents()).isEqualTo(0);
        assertThat(vo.getSummary().getClassAvgRate()).isEqualTo(0.0);
        assertThat(vo.getNodes().get(0).getRate()).isNull();
        assertThat(vo.getNodes().get(0).getUndiagnosed()).isEqualTo(0);
        assertThat(vo.getWeakRanking()).isEmpty();
    }

    @Test
    @DisplayName("衰减开启：raw 已掌握但证据陈旧 → 聚合按 effectiveLevel 计为薄弱，双均分手算一致")
    void aggregatesWithDecayHandCalculated() {
        LocalDateTime stale = LocalDateTime.now().minusDays(40);
        double effectiveStale = MasteryDecayCalculator.effectiveScore(88.0, 40.0, TAU_DAYS, DECAY_FLOOR, 2);
        int staleLevel = MasteryDecayCalculator.effectiveLevel(effectiveStale, 75.0, 40.0);

        when(dashboardMapper.countEnrolled(1L)).thenReturn(2);
        when(dashboardMapper.countDiagnosed(1L)).thenReturn(2);
        when(dashboardMapper.selectEnrolledMastery(1L)).thenReturn(Arrays.asList(
                sm(11, 100, "88", 2, stale),
                sm(12, 100, "88", 2, LocalDateTime.now())
        ));
        when(nodeMapper.selectList(any())).thenReturn(Arrays.asList(
                kn(100, "KT01", "软工概述", "概述")
        ));

        DashboardVO vo = service.dashboard(1L);
        DashboardVO.NodeStat kt01 = vo.getNodes().stream()
                .filter(n -> n.getNodeCode().equals("KT01")).findFirst().orElseThrow();

        assertThat(staleLevel).isEqualTo(1);
        assertThat(kt01.getMastered()).isEqualTo(1);
        assertThat(kt01.getWeak()).isEqualTo(1);
        assertThat(kt01.getRate()).isCloseTo(0.5, within(0.001));
        assertThat(kt01.getAvgRawScore()).isEqualTo(88.0);
        double expectedAvgEffective = (effectiveStale + 88.0) / 2.0;
        assertThat(kt01.getAvgEffectiveScore()).isCloseTo(expectedAvgEffective, within(0.1));
        assertThat(kt01.getAvgScore()).isEqualTo(kt01.getAvgRawScore());
        // mastered cells = 1; denom = 2*1 → 0.5
        assertThat(vo.getSummary().getClassAvgRate()).isCloseTo(0.5, within(0.001));

        verify(dashboardMapper).selectEnrolledMastery(1L);
    }
}
