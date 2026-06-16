package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.MasterySnapshot;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.MasterySnapshotMapper;
import com.wenjin.service.GrowthService;
import com.wenjin.vo.GrowthVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 成长档案服务实现：聚合快照生成成长曲线和前后对比。
 */
@Service
public class GrowthServiceImpl implements GrowthService {

    private final MasterySnapshotMapper snapshotMapper;
    private final KgNodeMapper nodeMapper;

    public GrowthServiceImpl(MasterySnapshotMapper snapshotMapper, KgNodeMapper nodeMapper) {
        this.snapshotMapper = snapshotMapper;
        this.nodeMapper = nodeMapper;
    }

    @Override
    public GrowthVO getGrowth(Long studentId, Long courseId) {
        // 1. 查询所有快照，按时间排序
        List<MasterySnapshot> snapshots = snapshotMapper.selectList(
            new LambdaQueryWrapper<MasterySnapshot>()
                .eq(MasterySnapshot::getStudentId, studentId)
                .eq(MasterySnapshot::getCourseId, courseId)
                .orderByAsc(MasterySnapshot::getSnapshotAt)
        );

        // 2. 空数据处理
        if (snapshots.isEmpty()) {
            return buildEmptyGrowth();
        }

        // 3. 构建成长曲线
        List<GrowthVO.CurvePoint> curve = buildCurve(snapshots);

        // 4. 构建前后对比
        GrowthVO.Compare compare = buildCompare(snapshots);

        // 5. 组装返回
        LocalDateTime startAt = snapshots.get(0).getSnapshotAt();
        return new GrowthVO(startAt, curve, compare);
    }

    /**
     * 构建成长曲线：每个快照时刻的整体掌握度均值。
     */
    private List<GrowthVO.CurvePoint> buildCurve(List<MasterySnapshot> snapshots) {
        List<GrowthVO.CurvePoint> curve = new ArrayList<>();

        // 按时间分组快照
        Map<LocalDateTime, List<MasterySnapshot>> snapshotsByTime = snapshots.stream()
            .collect(Collectors.groupingBy(MasterySnapshot::getSnapshotAt));

        // 维护每个节点的最新掌握度
        Map<Long, BigDecimal> latestScores = new HashMap<>();

        // 按时间顺序处理每个快照时刻
        List<LocalDateTime> timePoints = new ArrayList<>(snapshotsByTime.keySet());
        Collections.sort(timePoints);

        for (LocalDateTime time : timePoints) {
            List<MasterySnapshot> currentSnapshots = snapshotsByTime.get(time);

            // 更新这些节点的最新掌握度
            for (MasterySnapshot snap : currentSnapshots) {
                latestScores.put(snap.getNodeId(), snap.getMasteryScore());
            }

            // 计算当前时刻的整体掌握度（已触及节点的均值）
            if (!latestScores.isEmpty()) {
                BigDecimal sum = latestScores.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal mean = sum.divide(
                    new BigDecimal(latestScores.size()),
                    1,
                    RoundingMode.HALF_UP
                );
                curve.add(new GrowthVO.CurvePoint(time, mean));
            }
        }

        return curve;
    }

    /**
     * 构建前后对比：基线（首次快照）vs 当前（最新快照）。
     */
    private GrowthVO.Compare buildCompare(List<MasterySnapshot> snapshots) {
        // 分离首次和最新快照
        Map<Long, MasterySnapshot> baseline = new HashMap<>();
        Map<Long, MasterySnapshot> latest = new HashMap<>();

        for (MasterySnapshot snap : snapshots) {
            Long nodeId = snap.getNodeId();

            // 基线：首次出现
            if (!baseline.containsKey(nodeId)) {
                baseline.put(nodeId, snap);
            }

            // 最新：覆盖更新
            latest.put(nodeId, snap);
        }

        // 构建汇总统计
        GrowthVO.Summary summary = buildSummary(baseline, latest);

        // 构建章节面板
        List<GrowthVO.ChapterPanel> baselinePanels = buildPanels(baseline);
        List<GrowthVO.ChapterPanel> latestPanels = buildPanels(latest);

        return new GrowthVO.Compare(summary, baselinePanels, latestPanels);
    }

    /**
     * 构建汇总统计。
     */
    private GrowthVO.Summary buildSummary(
        Map<Long, MasterySnapshot> baseline,
        Map<Long, MasterySnapshot> latest
    ) {
        // 基线统计
        BigDecimal overallThen = calculateOverall(baseline.values());
        int masteredThen = countByLevel(baseline.values(), 2);
        int weakThen = countByLevel(baseline.values(), 1);
        int unlearnedThen = countByLevel(baseline.values(), 0);

        // 当前统计
        BigDecimal overallNow = calculateOverall(latest.values());
        int masteredNow = countByLevel(latest.values(), 2);
        int weakNow = countByLevel(latest.values(), 1);
        int unlearnedNow = countByLevel(latest.values(), 0);

        // 转绿统计：基线未掌握 → 当前掌握
        int turnedGreen = 0;
        for (Long nodeId : baseline.keySet()) {
            int baselineLevel = baseline.get(nodeId).getMasteryLevel();
            int latestLevel = latest.get(nodeId).getMasteryLevel();
            if (baselineLevel < 2 && latestLevel == 2) {
                turnedGreen++;
            }
        }

        return new GrowthVO.Summary(
            overallThen, overallNow,
            masteredThen, masteredNow,
            weakThen, weakNow,
            unlearnedThen, unlearnedNow,
            turnedGreen
        );
    }

    /**
     * 计算整体掌握度（均值，保留 1 位小数）。
     */
    private BigDecimal calculateOverall(Collection<MasterySnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return new BigDecimal("0.0");
        }

        BigDecimal sum = snapshots.stream()
            .map(MasterySnapshot::getMasteryScore)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return sum.divide(
            new BigDecimal(snapshots.size()),
            1,
            RoundingMode.HALF_UP
        );
    }

    /**
     * 统计指定等级的节点数量。
     */
    private int countByLevel(Collection<MasterySnapshot> snapshots, int level) {
        return (int) snapshots.stream()
            .filter(snap -> snap.getMasteryLevel() == level)
            .count();
    }

    /**
     * 构建章节面板：按章节分组节点。
     */
    private List<GrowthVO.ChapterPanel> buildPanels(Map<Long, MasterySnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量查询节点信息
        List<Long> nodeIds = new ArrayList<>(snapshots.keySet());
        Map<Long, KgNode> nodeMap = nodeMapper.selectBatchIds(nodeIds).stream()
            .collect(Collectors.toMap(KgNode::getId, node -> node));

        // 按章节分组
        Map<String, List<GrowthVO.NodeLevel>> chapterGroups = new LinkedHashMap<>();

        for (Map.Entry<Long, MasterySnapshot> entry : snapshots.entrySet()) {
            Long nodeId = entry.getKey();
            MasterySnapshot snap = entry.getValue();
            KgNode node = nodeMap.get(nodeId);

            if (node != null) {
                String chapter = node.getChapter();
                chapterGroups.putIfAbsent(chapter, new ArrayList<>());

                GrowthVO.NodeLevel nodeLevel = new GrowthVO.NodeLevel(
                    node.getNodeCode(),
                    node.getName(),
                    snap.getMasteryLevel()
                );
                chapterGroups.get(chapter).add(nodeLevel);
            }
        }

        // 转换为面板列表并按章节名排序
        List<GrowthVO.ChapterPanel> panels = chapterGroups.entrySet().stream()
            .map(e -> new GrowthVO.ChapterPanel(e.getKey(), e.getValue()))
            .collect(Collectors.toList());
        panels.sort(Comparator.comparing(GrowthVO.ChapterPanel::getChapter));
        return panels;
    }

    /**
     * 构建空数据响应。
     */
    private GrowthVO buildEmptyGrowth() {
        GrowthVO.Summary summary = new GrowthVO.Summary(
            new BigDecimal("0.0"), new BigDecimal("0.0"),
            0, 0, 0, 0, 0, 0, 0
        );
        GrowthVO.Compare compare = new GrowthVO.Compare(
            summary,
            Collections.emptyList(),
            Collections.emptyList()
        );
        return new GrowthVO(null, Collections.emptyList(), compare);
    }
}
