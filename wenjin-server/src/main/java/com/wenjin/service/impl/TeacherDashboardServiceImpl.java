package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.dto.DashboardVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.TeacherDashboardMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.TeacherDashboardService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 学情看板服务实现：roster（student_course）为分母口径，掌握行经读时衰减后聚合。
 * <p>
 * 统计口径基于读时衰减后的有效等级与分值（含时间衰减），反映班级当前真实学情。
 * ghost mastery（未选课学生）不计入；未诊断人数 = roster − 该节点有 mastery 的选课学生数。
 */
@Service
public class TeacherDashboardServiceImpl implements TeacherDashboardService {

    private static final int WEAK_TOP_N = 8;

    private final TeacherDashboardMapper dashboardMapper;
    private final KgNodeMapper nodeMapper;
    private final MasteryDecayCalculator decayCalculator;
    private final CourseAccessService courseAccessService;

    public TeacherDashboardServiceImpl(TeacherDashboardMapper dashboardMapper, KgNodeMapper nodeMapper,
                                       MasteryDecayCalculator decayCalculator,
                                       CourseAccessService courseAccessService) {
        this.dashboardMapper = dashboardMapper;
        this.nodeMapper = nodeMapper;
        this.decayCalculator = decayCalculator;
        this.courseAccessService = courseAccessService;
    }

    @Override
    public DashboardVO dashboard(Long courseId) {
        courseAccessService.assertManageCourse(courseId);

        int totalStudents = dashboardMapper.countEnrolled(courseId);
        int diagnosedStudents = dashboardMapper.countDiagnosed(courseId);

        List<KgNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId)
                .isNull(KgNode::getArchivedAt)
                .orderByAsc(KgNode::getId));

        // roster 驱动的掌握行（已排除 ghost / 归档节点）
        List<StudentMastery> rows = dashboardMapper.selectEnrolledMastery(courseId);

        Map<Long, List<StudentMastery>> byNode = rows.stream()
                .collect(Collectors.groupingBy(StudentMastery::getNodeId));

        List<DashboardVO.NodeStat> nodeStats = new ArrayList<>();
        int masteredCells = 0;
        for (KgNode n : nodes) {
            List<StudentMastery> g = byNode.getOrDefault(n.getId(), Collections.emptyList());
            DashboardVO.NodeStat st = new DashboardVO.NodeStat();
            st.setNodeCode(n.getNodeCode());
            st.setName(n.getName());
            st.setChapter(n.getChapter());
            int mastered = 0, weak = 0;
            BigDecimal rawSum = BigDecimal.ZERO;
            BigDecimal effectiveSum = BigDecimal.ZERO;
            int scored = 0;
            for (StudentMastery m : g) {
                if (m.getMasteryScore() == null) {
                    continue;
                }
                scored++;
                double raw = m.getMasteryScore().doubleValue();
                double effective = decayCalculator.effectiveScoreOf(m);
                int level = decayCalculator.effectiveLevelOf(effective);
                if (level == 2) {
                    mastered++;
                } else if (level == 1) {
                    weak++;
                }
                rawSum = rawSum.add(m.getMasteryScore());
                effectiveSum = effectiveSum.add(BigDecimal.valueOf(effective));
            }
            masteredCells += mastered;
            st.setMastered(mastered);
            st.setWeak(weak);
            // undiagnosed = roster − 该节点有 mastery 的选课学生数
            st.setUndiagnosed(Math.max(0, totalStudents - g.size()));
            if (scored == 0) {
                st.setAvgRawScore(null);
                st.setAvgEffectiveScore(null);
                st.setAvgScore(null);
            } else {
                BigDecimal divisor = new BigDecimal(scored);
                Double avgRaw = rawSum.divide(divisor, 1, RoundingMode.HALF_UP).doubleValue();
                Double avgEffective = effectiveSum.divide(divisor, 1, RoundingMode.HALF_UP).doubleValue();
                st.setAvgRawScore(avgRaw);
                st.setAvgEffectiveScore(avgEffective);
                st.setAvgScore(avgRaw);
            }
            // 节点掌握率仍为 mastered/(mastered+weak)；百分比条用 roster 作分母
            Double rate = (mastered + weak) == 0 ? null : (double) mastered / (mastered + weak);
            st.setRate(rate);
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
                    w.setNodeCode(s.getNodeCode());
                    w.setName(s.getName());
                    w.setChapter(s.getChapter());
                    w.setWeak(s.getWeak());
                    w.setRate(s.getRate());
                    // 百分比以 roster 为分母
                    w.setMasteredPct(totalStudents == 0 ? 0 : s.getMastered() * 100 / totalStudents);
                    w.setWeakPct(totalStudents == 0 ? 0 : s.getWeak() * 100 / totalStudents);
                    return w;
                })
                .collect(Collectors.toList());

        // classAvgRate = mastered cells / (roster × active nodes)；分母为 0 时返回 0
        int denom = totalStudents * nodes.size();
        Double classAvgRate = denom == 0
                ? 0.0
                : Math.round((double) masteredCells / denom * 1000.0) / 1000.0;

        DashboardVO.Summary summary = new DashboardVO.Summary();
        summary.setTotalStudents(totalStudents);
        summary.setDiagnosedStudents(diagnosedStudents);
        summary.setClassAvgRate(classAvgRate);
        summary.setNodeCount(nodes.size());

        DashboardVO vo = new DashboardVO();
        vo.setSummary(summary);
        vo.setNodes(nodeStats);
        vo.setWeakRanking(ranking);
        return vo;
    }

}
