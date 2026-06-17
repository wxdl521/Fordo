package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.dto.DashboardVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.TeacherDashboardService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/** 学情看板服务实现：Java 内对 student_mastery 按节点聚合【阶段六】。 */
@Service
public class TeacherDashboardServiceImpl implements TeacherDashboardService {

    private static final int WEAK_TOP_N = 8;

    private final StudentMasteryMapper masteryMapper;
    private final KgNodeMapper nodeMapper;

    public TeacherDashboardServiceImpl(StudentMasteryMapper masteryMapper, KgNodeMapper nodeMapper) {
        this.masteryMapper = masteryMapper;
        this.nodeMapper = nodeMapper;
    }

    @Override
    public DashboardVO dashboard(Long courseId) {
        List<StudentMastery> rows = masteryMapper.selectList(new LambdaQueryWrapper<StudentMastery>()
                .eq(StudentMastery::getCourseId, courseId));
        List<KgNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId).orderByAsc(KgNode::getId));

        // 总学生数 = distinct student_id
        Set<Long> students = rows.stream().map(StudentMastery::getStudentId).collect(Collectors.toSet());
        int totalStudents = students.size();

        // 按 nodeId 分组
        Map<Long, List<StudentMastery>> byNode = rows.stream()
                .collect(Collectors.groupingBy(StudentMastery::getNodeId));

        List<DashboardVO.NodeStat> nodeStats = new ArrayList<>();
        double rateSum = 0; int rateCnt = 0;
        for (KgNode n : nodes) {
            List<StudentMastery> g = byNode.getOrDefault(n.getId(), Collections.emptyList());
            DashboardVO.NodeStat st = new DashboardVO.NodeStat();
            st.setNodeCode(n.getNodeCode()); st.setName(n.getName()); st.setChapter(n.getChapter());
            int mastered = 0, weak = 0; BigDecimal sum = BigDecimal.ZERO;
            for (StudentMastery m : g) {
                if (m.getMasteryLevel() != null && m.getMasteryLevel() == 2) mastered++;
                else if (m.getMasteryLevel() != null && m.getMasteryLevel() == 1) weak++;
                if (m.getMasteryScore() != null) sum = sum.add(m.getMasteryScore());
            }
            st.setMastered(mastered); st.setWeak(weak);
            st.setUndiagnosed(Math.max(0, totalStudents - g.size()));
            st.setAvgScore(g.isEmpty() ? null
                    : sum.divide(new BigDecimal(g.size()), 1, RoundingMode.HALF_UP).doubleValue());
            Double rate = (mastered + weak) == 0 ? null : (double) mastered / (mastered + weak);
            st.setRate(rate);
            if (rate != null) { rateSum += rate; rateCnt++; }
            nodeStats.add(st);
        }

        // 薄弱排行：weak 降序，并列 rate 升序（null rate 视为最低）
        List<DashboardVO.WeakItem> ranking = nodeStats.stream()
                .filter(s -> s.getWeak() > 0)
                .sorted(Comparator.<DashboardVO.NodeStat>comparingInt(DashboardVO.NodeStat::getWeak).reversed()
                        .thenComparing(s -> s.getRate() == null ? -1.0 : s.getRate()))
                .limit(WEAK_TOP_N)
                .map(s -> {
                    DashboardVO.WeakItem w = new DashboardVO.WeakItem();
                    w.setNodeCode(s.getNodeCode()); w.setName(s.getName()); w.setChapter(s.getChapter());
                    w.setWeak(s.getWeak()); w.setRate(s.getRate());
                    w.setMasteredPct(totalStudents == 0 ? 0 : s.getMastered() * 100 / totalStudents);
                    w.setWeakPct(totalStudents == 0 ? 0 : s.getWeak() * 100 / totalStudents);
                    return w;
                })
                .collect(Collectors.toList());

        DashboardVO.Summary summary = new DashboardVO.Summary();
        summary.setTotalStudents(totalStudents);
        summary.setDiagnosedStudents(totalStudents);   // 有掌握行即已诊断
        summary.setClassAvgRate(rateCnt == 0 ? null
                : Math.round(rateSum / rateCnt * 1000.0) / 1000.0);
        summary.setNodeCount(nodes.size());

        DashboardVO vo = new DashboardVO();
        vo.setSummary(summary); vo.setNodes(nodeStats); vo.setWeakRanking(ranking);
        return vo;
    }
}
