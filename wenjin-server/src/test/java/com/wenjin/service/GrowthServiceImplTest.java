package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.MasterySnapshot;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.MasterySnapshotMapper;
import com.wenjin.service.impl.GrowthServiceImpl;
import com.wenjin.vo.GrowthVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @InjectMocks
    private GrowthServiceImpl service;

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
    }
}
