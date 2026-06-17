package com.wenjin.service;

import com.wenjin.dto.DashboardVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.impl.TeacherDashboardServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherDashboardServiceImplTest {

    @Mock StudentMasteryMapper masteryMapper;
    @Mock KgNodeMapper nodeMapper;
    @InjectMocks TeacherDashboardServiceImpl service;

    private StudentMastery sm(long stu, long node, String score, int lvl) {
        StudentMastery m = new StudentMastery(); m.setStudentId(stu); m.setCourseId(1L);
        m.setNodeId(node); m.setMasteryScore(new BigDecimal(score)); m.setMasteryLevel(lvl); return m;
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
}
