package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.MasterySnapshot;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.MasterySnapshotMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.impl.GrowthServiceImpl;
import com.wenjin.vo.GrowthVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * GrowthServiceImpl 测试（TDD 3 例）。
 */
@ExtendWith(MockitoExtension.class)
class GrowthServiceImplTest {

    @Mock
    private MasterySnapshotMapper snapshotMapper;

    @Mock
    private KgNodeMapper nodeMapper;

    @Mock
    private StudentMasteryMapper studentMasteryMapper;

    @InjectMocks
    private GrowthServiceImpl service;

    private static final double TAU_DAYS = 30.0;
    private static final double DECAY_FLOOR = 25.0;

    private void enableDecay() {
        ReflectionTestUtils.setField(service, "decayEnabled", true);
        ReflectionTestUtils.setField(service, "decayTauDays", TAU_DAYS);
        ReflectionTestUtils.setField(service, "decayFloor", DECAY_FLOOR);
    }

    /**
     * 测试 1：曲线点数量与趋势验证。
     * 验证：曲线点数 == 快照数，掌握度递增。
     */
    @Test
    void curveCountAndTrend() {
        Long studentId = 1L;
        Long courseId = 10L;

        // Mock：3 次快照，同一节点掌握度递增
        LocalDateTime t1 = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2024, 1, 2, 10, 0);
        LocalDateTime t3 = LocalDateTime.of(2024, 1, 3, 10, 0);

        MasterySnapshot snap1 = new MasterySnapshot();
        snap1.setStudentId(studentId);
        snap1.setCourseId(courseId);
        snap1.setNodeId(100L);
        snap1.setMasteryScore(new BigDecimal("30.0"));
        snap1.setMasteryLevel(1);  // weak
        snap1.setSnapshotAt(t1);

        MasterySnapshot snap2 = new MasterySnapshot();
        snap2.setStudentId(studentId);
        snap2.setCourseId(courseId);
        snap2.setNodeId(100L);
        snap2.setMasteryScore(new BigDecimal("50.0"));
        snap2.setMasteryLevel(1);
        snap2.setSnapshotAt(t2);

        MasterySnapshot snap3 = new MasterySnapshot();
        snap3.setStudentId(studentId);
        snap3.setCourseId(courseId);
        snap3.setNodeId(100L);
        snap3.setMasteryScore(new BigDecimal("70.0"));
        snap3.setMasteryLevel(2);  // mastered
        snap3.setSnapshotAt(t3);

        when(snapshotMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(Arrays.asList(snap1, snap2, snap3));

        // Mock：节点批量查询
        KgNode node = new KgNode();
        node.setId(100L);
        node.setNodeCode("KT12");
        node.setName("二次函数基础");
        node.setChapter("第一章");
        when(nodeMapper.selectBatchIds(Arrays.asList(100L))).thenReturn(Arrays.asList(node));

        GrowthVO result = service.getGrowth(studentId, courseId);

        // 验证曲线点数
        assertEquals(3, result.getCurve().size());

        // 验证起始时间
        assertEquals(t1, result.getStartAt());

        // 验证掌握度递增
        List<GrowthVO.CurvePoint> curve = result.getCurve();
        assertTrue(curve.get(0).getOverallMastery().compareTo(new BigDecimal("30.0")) == 0);
        assertTrue(curve.get(1).getOverallMastery().compareTo(new BigDecimal("50.0")) == 0);
        assertTrue(curve.get(2).getOverallMastery().compareTo(new BigDecimal("70.0")) == 0);
    }

    /**
     * 测试 2：前后对比转绿统计。
     * 验证：节点从薄弱到掌握，turnedGreen = 1。
     */
    @Test
    void compareTurnedGreen() {
        Long studentId = 2L;
        Long courseId = 20L;

        LocalDateTime t1 = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2024, 1, 5, 10, 0);

        // 节点 A：从薄弱到掌握
        MasterySnapshot snap1A = new MasterySnapshot();
        snap1A.setStudentId(studentId);
        snap1A.setCourseId(courseId);
        snap1A.setNodeId(200L);
        snap1A.setMasteryScore(new BigDecimal("45.0"));
        snap1A.setMasteryLevel(1);  // weak
        snap1A.setSnapshotAt(t1);

        MasterySnapshot snap2A = new MasterySnapshot();
        snap2A.setStudentId(studentId);
        snap2A.setCourseId(courseId);
        snap2A.setNodeId(200L);
        snap2A.setMasteryScore(new BigDecimal("75.0"));
        snap2A.setMasteryLevel(2);  // mastered
        snap2A.setSnapshotAt(t2);

        when(snapshotMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(Arrays.asList(snap1A, snap2A));

        // Mock：节点批量查询
        KgNode nodeA = new KgNode();
        nodeA.setId(200L);
        nodeA.setNodeCode("KT20");
        nodeA.setName("一元二次方程");
        nodeA.setChapter("第二章");
        when(nodeMapper.selectBatchIds(Arrays.asList(200L))).thenReturn(Arrays.asList(nodeA));

        GrowthVO result = service.getGrowth(studentId, courseId);

        // 验证转绿数量
        assertEquals(1, result.getCompare().getSummary().getTurnedGreen());

        // 验证掌握数量变化
        assertEquals(0, result.getCompare().getSummary().getMasteredThen());
        assertEquals(1, result.getCompare().getSummary().getMasteredNow());

        // 验证薄弱数量变化
        assertEquals(1, result.getCompare().getSummary().getWeakThen());
        assertEquals(0, result.getCompare().getSummary().getWeakNow());
    }

    /**
     * 测试 3：空数据处理。
     * 验证：无快照时返回空曲线、零统计、空面板。
     */
    @Test
    void emptyData() {
        Long studentId = 3L;
        Long courseId = 30L;

        when(snapshotMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(Collections.emptyList());

        GrowthVO result = service.getGrowth(studentId, courseId);

        // 验证空曲线
        assertTrue(result.getCurve().isEmpty());
        assertNull(result.getStartAt());

        // 验证零统计
        GrowthVO.Summary summary = result.getCompare().getSummary();
        assertEquals(new BigDecimal("0.0"), summary.getOverallThen());
        assertEquals(new BigDecimal("0.0"), summary.getOverallNow());
        assertEquals(0, summary.getMasteredThen());
        assertEquals(0, summary.getMasteredNow());
        assertEquals(0, summary.getWeakThen());
        assertEquals(0, summary.getWeakNow());
        assertEquals(0, summary.getUnlearnedThen());
        assertEquals(0, summary.getUnlearnedNow());
        assertEquals(0, summary.getTurnedGreen());

        // 验证空面板
        assertTrue(result.getCompare().getBaseline().isEmpty());
        assertTrue(result.getCompare().getLatest().isEmpty());
        assertNull(result.getEffectiveMarker());
    }

    /**
     * 测试 4：历史曲线保持原始分，终点标记反映读时衰减。
     */
    @Test
    void curveRawUnchanged_effectiveMarkerReflectsDecay() {
        Long studentId = 4L;
        Long courseId = 40L;
        enableDecay();

        LocalDateTime t1 = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2024, 1, 5, 10, 0);

        MasterySnapshot snap1 = new MasterySnapshot();
        snap1.setStudentId(studentId);
        snap1.setCourseId(courseId);
        snap1.setNodeId(400L);
        snap1.setMasteryScore(new BigDecimal("80.0"));
        snap1.setMasteryLevel(2);
        snap1.setSnapshotAt(t1);

        MasterySnapshot snap2 = new MasterySnapshot();
        snap2.setStudentId(studentId);
        snap2.setCourseId(courseId);
        snap2.setNodeId(400L);
        snap2.setMasteryScore(new BigDecimal("88.0"));
        snap2.setMasteryLevel(2);
        snap2.setSnapshotAt(t2);

        when(snapshotMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(Arrays.asList(snap1, snap2));

        KgNode node = new KgNode();
        node.setId(400L);
        node.setNodeCode("KT40");
        node.setName("衰减演示");
        node.setChapter("第三章");
        when(nodeMapper.selectBatchIds(Arrays.asList(400L))).thenReturn(Arrays.asList(node));

        LocalDateTime stale = LocalDateTime.now().minusDays(40);
        StudentMastery mastery = new StudentMastery();
        mastery.setStudentId(studentId);
        mastery.setCourseId(courseId);
        mastery.setNodeId(400L);
        mastery.setMasteryScore(new BigDecimal("88.0"));
        mastery.setMasteryLevel(2);
        mastery.setUpdatedAt(stale);
        when(studentMasteryMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(Collections.singletonList(mastery));

        GrowthVO result = service.getGrowth(studentId, courseId);

        // 历史曲线仍为快照原始分
        assertEquals(2, result.getCurve().size());
        assertEquals(0, result.getCurve().get(0).getOverallMastery().compareTo(new BigDecimal("80.0")));
        assertEquals(0, result.getCurve().get(1).getOverallMastery().compareTo(new BigDecimal("88.0")));

        // 终点标记反映 40 天衰减后的有效分（低于曲线末点 88）
        assertNotNull(result.getEffectiveMarker());
        double expectedEffective = MasteryDecayCalculator.effectiveScore(88.0, 40.0, TAU_DAYS, DECAY_FLOOR, 2);
        BigDecimal expectedMean = BigDecimal.valueOf(expectedEffective).setScale(1, java.math.RoundingMode.HALF_UP);
        assertEquals(0, result.getEffectiveMarker().getOverallEffectiveMastery().compareTo(expectedMean));
        assertTrue(result.getEffectiveMarker().getOverallEffectiveMastery()
            .compareTo(new BigDecimal("88.0")) < 0);
        assertNotNull(result.getEffectiveMarker().getAsOf());
    }

    /**
     * 测试 5：effectiveMarker 跳过 masteryScore 为 null 的行。
     * 验证：3 行中 1 行 masteryScore=null，其余 2 行有分值、updatedAt=now（不衰减）
     * → 不抛 NPE，effectiveMarker.overallEffectiveMastery 等于这 2 行的均值。
     */
    @Test
    void effectiveMarker_skipNullMasteryScore() {
        Long studentId = 5L;
        Long courseId = 50L;

        // 至少需要 1 快照，避免提前走 empty 分支
        LocalDateTime tSnapshot = LocalDateTime.of(2024, 1, 1, 10, 0);
        MasterySnapshot snap = new MasterySnapshot();
        snap.setStudentId(studentId);
        snap.setCourseId(courseId);
        snap.setNodeId(500L);
        snap.setMasteryScore(new BigDecimal("70.0"));
        snap.setMasteryLevel(2);
        snap.setSnapshotAt(tSnapshot);
        when(snapshotMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(Collections.singletonList(snap));

        KgNode node = new KgNode();
        node.setId(500L);
        node.setNodeCode("KT50");
        node.setName("节点 A");
        node.setChapter("第四章");
        when(nodeMapper.selectBatchIds(Arrays.asList(500L))).thenReturn(Arrays.asList(node));

        // 3 个 StudentMastery，其中 1 个 masteryScore=null
        LocalDateTime now = LocalDateTime.now();
        StudentMastery sm1 = new StudentMastery();
        sm1.setStudentId(studentId);
        sm1.setCourseId(courseId);
        sm1.setNodeId(501L);
        sm1.setMasteryScore(new BigDecimal("60.0"));
        sm1.setMasteryLevel(2);
        sm1.setUpdatedAt(now);

        StudentMastery sm2 = new StudentMastery();
        sm2.setStudentId(studentId);
        sm2.setCourseId(courseId);
        sm2.setNodeId(502L);
        sm2.setMasteryScore(null);  // 这一行会被跳过
        sm2.setMasteryLevel(1);
        sm2.setUpdatedAt(now);

        StudentMastery sm3 = new StudentMastery();
        sm3.setStudentId(studentId);
        sm3.setCourseId(courseId);
        sm3.setNodeId(503L);
        sm3.setMasteryScore(new BigDecimal("80.0"));
        sm3.setMasteryLevel(2);
        sm3.setUpdatedAt(now);

        when(studentMasteryMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(Arrays.asList(sm1, sm2, sm3));

        // 调用 getGrowth，应不抛 NPE
        GrowthVO result = service.getGrowth(studentId, courseId);

        // 验证 effectiveMarker 存在且等于 2 行的均值：(60 + 80) / 2 = 70
        assertNotNull(result.getEffectiveMarker());
        BigDecimal expected = new BigDecimal("70.0");
        assertEquals(0, result.getEffectiveMarker().getOverallEffectiveMastery().compareTo(expected));
    }

    /**
     * 测试 6：全部行 masteryScore 为 null 时 effectiveMarker 为 null。
     * 验证：2 行全部 masteryScore=null → getEffectiveMarker() 返回 null（不能除以 0）。
     */
    @Test
    void effectiveMarker_allNullMasteryScore() {
        Long studentId = 6L;
        Long courseId = 60L;

        // 至少需要 1 快照
        LocalDateTime tSnapshot = LocalDateTime.of(2024, 1, 1, 10, 0);
        MasterySnapshot snap = new MasterySnapshot();
        snap.setStudentId(studentId);
        snap.setCourseId(courseId);
        snap.setNodeId(600L);
        snap.setMasteryScore(new BigDecimal("50.0"));
        snap.setMasteryLevel(1);
        snap.setSnapshotAt(tSnapshot);
        when(snapshotMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(Collections.singletonList(snap));

        KgNode node = new KgNode();
        node.setId(600L);
        node.setNodeCode("KT60");
        node.setName("节点 B");
        node.setChapter("第五章");
        when(nodeMapper.selectBatchIds(Arrays.asList(600L))).thenReturn(Arrays.asList(node));

        // 2 个 StudentMastery，全部 masteryScore=null
        LocalDateTime now = LocalDateTime.now();
        StudentMastery sm1 = new StudentMastery();
        sm1.setStudentId(studentId);
        sm1.setCourseId(courseId);
        sm1.setNodeId(601L);
        sm1.setMasteryScore(null);
        sm1.setMasteryLevel(0);
        sm1.setUpdatedAt(now);

        StudentMastery sm2 = new StudentMastery();
        sm2.setStudentId(studentId);
        sm2.setCourseId(courseId);
        sm2.setNodeId(602L);
        sm2.setMasteryScore(null);
        sm2.setMasteryLevel(0);
        sm2.setUpdatedAt(now);

        when(studentMasteryMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(Arrays.asList(sm1, sm2));

        // 调用 getGrowth
        GrowthVO result = service.getGrowth(studentId, courseId);

        // 验证 effectiveMarker 为 null（不能除以 0）
        assertNull(result.getEffectiveMarker());
    }
}
