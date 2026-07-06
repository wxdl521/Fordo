package com.wenjin.service;

import com.wenjin.dto.DashboardVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.impl.TeacherDashboardServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherDashboardServiceImplTest {

    @Mock StudentMasteryMapper masteryMapper;
    @Mock KgNodeMapper nodeMapper;

    private TeacherDashboardServiceImpl service;

    private static final double TAU_DAYS = 30.0;
    private static final double DECAY_FLOOR = 25.0;

    /** 小工厂：构建真实 MasteryDecayCalculator 并注入服务，避免在每个用例重复 setField。 */
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
        service = new TeacherDashboardServiceImpl(masteryMapper, nodeMapper, calculator(true));
    }

    private StudentMastery sm(long stu, long node, String score, int lvl) {
        return sm(stu, node, score, lvl, LocalDateTime.now());
    }

    private StudentMastery sm(long stu, long node, String score, int lvl, LocalDateTime updatedAt) {
        StudentMastery m = new StudentMastery(); m.setStudentId(stu); m.setCourseId(1L);
        m.setNodeId(node); m.setMasteryScore(new BigDecimal(score)); m.setMasteryLevel(lvl);
        m.setUpdatedAt(updatedAt);
        return m;
    }
    private KgNode kn(long id, String code, String name, String chap) {
        KgNode n = new KgNode(); n.setId(id); n.setCourseId(1L); n.setNodeCode(code); n.setName(name); n.setChapter(chap); return n;
    }

    @Test
    void aggregatesPerNodeAndRanksWeak() {
        // 节点 100（KT01）：甲已掌握/乙薄弱/丙薄弱；节点 200（KT20）：甲薄弱/乙未学/丙未学
        when(masteryMapper.selectList(any())).thenReturn(Arrays.asList(
            sm(11,100,"88",2), sm(12,100,"60",1), sm(13,100,"55",1),
            sm(11,200,"55",1), sm(12,200,"35",0), sm(13,200,"20",0)
        ));
        when(nodeMapper.selectList(any())).thenReturn(Arrays.asList(
            kn(100,"KT01","软工概述","概述"), kn(200,"KT20","类设计转代码","对象设计")
        ));

        DashboardVO vo = service.dashboard(1L);

        assertThat(vo.getSummary().getTotalStudents()).isEqualTo(3);
        assertThat(vo.getSummary().getNodeCount()).isEqualTo(2);

        DashboardVO.NodeStat kt01 = vo.getNodes().stream().filter(n -> n.getNodeCode().equals("KT01")).findFirst().orElseThrow();
        assertThat(kt01.getMastered()).isEqualTo(1);
        assertThat(kt01.getWeak()).isEqualTo(2);
        assertThat(kt01.getUndiagnosed()).isEqualTo(0);     // 3 学生都有行
        assertThat(kt01.getRate()).isEqualTo(1.0/3);        // 1/(1+2)

        DashboardVO.NodeStat kt20 = vo.getNodes().stream().filter(n -> n.getNodeCode().equals("KT20")).findFirst().orElseThrow();
        assertThat(kt20.getWeak()).isEqualTo(1);

        // 薄弱排行：KT01 weak=2 在 KT20 weak=1 之前
        assertThat(vo.getWeakRanking().get(0).getNodeCode()).isEqualTo("KT01");
    }

    @Test
    void emptyWhenNoMastery() {
        when(masteryMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());
        when(nodeMapper.selectList(any())).thenReturn(Arrays.asList(kn(100,"KT01","软工概述","概述")));
        DashboardVO vo = service.dashboard(1L);
        assertThat(vo.getSummary().getTotalStudents()).isEqualTo(0);
        assertThat(vo.getNodes().get(0).getRate()).isNull();
        assertThat(vo.getWeakRanking()).isEmpty();
    }

    @Test
    @DisplayName("衰减开启：raw 已掌握但证据陈旧 → 聚合按 effectiveLevel 计为薄弱，双均分手算一致")
    void aggregatesWithDecayHandCalculated() {
        LocalDateTime stale = LocalDateTime.now().minusDays(40);
        double effectiveStale = MasteryDecayCalculator.effectiveScore(88.0, 40.0, TAU_DAYS, DECAY_FLOOR, 2);
        int staleLevel = MasteryDecayCalculator.effectiveLevel(effectiveStale, 75.0, 40.0);

        when(masteryMapper.selectList(any())).thenReturn(Arrays.asList(
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
    }
}
