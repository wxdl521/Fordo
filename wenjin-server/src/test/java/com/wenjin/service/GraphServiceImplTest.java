package com.wenjin.service;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.GraphDataVO;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.dto.GraphImportResult;
import com.wenjin.dto.GraphValidateResult;
import com.wenjin.entity.Course;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.impl.GraphServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GraphServiceImpl 单元测试：校验、差量对账保主键、查询 VO 映射。
 */
@ExtendWith(MockitoExtension.class)
class GraphServiceImplTest {

    @Mock CourseMapper courseMapper;
    @Mock KgNodeMapper nodeMapper;
    @Mock KgEdgeMapper edgeMapper;
    @Mock StudentMasteryMapper studentMasteryMapper;
    @Mock CourseAccessService courseAccessService;

    private GraphServiceImpl service() {
        return serviceWithDecay(true, true);
    }

    private MasteryDecayCalculator calculator(boolean enabled) {
        MasteryDecayCalculator calc = new MasteryDecayCalculator();
        ReflectionTestUtils.setField(calc, "enabled", enabled);
        ReflectionTestUtils.setField(calc, "tauDays", 30.0);
        ReflectionTestUtils.setField(calc, "floor", 25.0);
        ReflectionTestUtils.setField(calc, "masteredThreshold", 75.0);
        ReflectionTestUtils.setField(calc, "weakThreshold", 40.0);
        return calc;
    }

    private GraphServiceImpl serviceWithDecay(boolean decayEnabled, boolean reconcileEnabled) {
        lenient().when(courseAccessService.assertManageCourseCode(anyString())).thenReturn(1L);
        FeatureFlagsProperties flags = new FeatureFlagsProperties();
        flags.setGraphReconcileEnabled(reconcileEnabled);
        return new GraphServiceImpl(courseMapper, nodeMapper, edgeMapper, studentMasteryMapper,
                calculator(decayEnabled), courseAccessService, flags);
    }

    // ───────── 校验类 ─────────

    @Test
    @DisplayName("nodes 为空时按参数错误拒绝")
    void rejectsEmptyNodes() {
        GraphImportRequest req = new GraphImportRequest();
        req.setNodes(new ArrayList<>());
        assertThatThrownBy(() -> service().importGraph("C1", req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.BAD_REQUEST.getCode());
    }

    @Test
    @DisplayName("特性关闭 → import 503")
    void import_featureDisabled_503() {
        GraphImportRequest req = req(List.of(node("KT01", "A")), List.of());
        assertThatThrownBy(() -> serviceWithDecay(true, false).importGraph("C1", req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
        verify(nodeMapper, never()).insert(any(KgNode.class));
        verify(nodeMapper, never()).delete(any());
    }

    @Test
    @DisplayName("节点 ID 重复被拒，明细含 DUPLICATE_NODE_ID")
    void rejectsDuplicateNodeId() {
        GraphImportRequest req = req(
                List.of(node("KT01", "概述"), node("KT01", "重复")),
                List.of());
        GraphValidateResult vr = expectValidateFail(req);
        assertThat(categories(vr)).contains("DUPLICATE_NODE_ID");
        assertThat(messages(vr)).anyMatch(m -> m.contains("KT01"));
    }

    @Test
    @DisplayName("边端点不在节点集时被拒，明细含 MISSING_NODE 且指出缺失 ID")
    void rejectsMissingEdgeEndpoint() {
        GraphImportRequest req = req(
                List.of(node("KT01", "概述")),
                List.of(edge("KT01", "KT999", "前置")));
        GraphValidateResult vr = expectValidateFail(req);
        assertThat(categories(vr)).contains("MISSING_NODE");
        assertThat(messages(vr)).anyMatch(m -> m.contains("KT999"));
    }

    @Test
    @DisplayName("非法关系类型被拒，明细含 BAD_RELATION_TYPE")
    void rejectsInvalidRelationType() {
        GraphImportRequest req = req(
                List.of(node("KT01", "A"), node("KT02", "B")),
                List.of(edge("KT01", "KT02", "依赖")));
        GraphValidateResult vr = expectValidateFail(req);
        assertThat(categories(vr)).contains("BAD_RELATION_TYPE");
    }

    @Test
    @DisplayName("前置边成环时被拒，环路径首尾相接")
    void detectsPrerequisiteCycle() {
        GraphImportRequest req = req(
                List.of(node("KT01", "A"), node("KT02", "B")),
                List.of(edge("KT01", "KT02", "前置"), edge("KT02", "KT01", "前置")));
        GraphValidateResult vr = expectValidateFail(req);
        assertThat(categories(vr)).contains("CYCLE");
        String cycle = messages(vr).stream().filter(m -> m.contains("环路")).findFirst().orElse("");
        assertThat(cycle).contains("KT01").contains("KT02").contains("→");
    }

    @Test
    @DisplayName("节点缺少 id 或 name 时报 EMPTY")
    void rejectsEmptyIdOrName() {
        GraphImportRequest.NodeItem noId = node(" ", "无ID");
        GraphImportRequest.NodeItem noName = node("KT02", " ");
        GraphImportRequest req = req(List.of(noId, noName), List.of());
        GraphValidateResult vr = expectValidateFail(req);
        assertThat(categories(vr)).contains("EMPTY");
        assertThat(messages(vr)).anyMatch(m -> m.contains("缺少 id"));
        assertThat(messages(vr)).anyMatch(m -> m.contains("缺少 name"));
    }

    @Test
    @DisplayName("多节点多类型边的合法 DAG 落库（首导 insert）")
    void validDagWithMixedEdgesImports() {
        Course course = course(1L, "C1", "课程一");
        when(courseMapper.selectById(1L)).thenReturn(course);
        when(nodeMapper.selectList(any())).thenReturn(List.of());
        when(edgeMapper.selectList(any())).thenReturn(List.of());
        when(nodeMapper.insert(any(KgNode.class))).thenAnswer(inv -> {
            KgNode n = inv.getArgument(0);
            n.setId(Long.parseLong(n.getNodeCode().substring(2)));
            return 1;
        });
        GraphImportRequest req = req(
                List.of(node("KT01", "A"), node("KT02", "B"), node("KT03", "C")),
                List.of(edge("KT01", "KT02", "前置"),
                        edge("KT02", "KT03", "前置"),
                        edge("KT01", "KT03", "相关"),
                        edge("KT02", "KT03", "包含")));

        GraphImportResult result = service().importGraph("C1", req);

        assertThat(result.getNodeCount()).isEqualTo(3);
        assertThat(result.getEdgeCount()).isEqualTo(4);
        verify(nodeMapper, never()).delete(any());
        verify(edgeMapper, never()).delete(any());
    }

    @Test
    @DisplayName("自环前置边也能被检出")
    void detectsSelfLoopCycle() {
        GraphImportRequest req = req(
                List.of(node("KT01", "A")),
                List.of(edge("KT01", "KT01", "前置")));
        GraphValidateResult vr = expectValidateFail(req);
        assertThat(categories(vr)).contains("CYCLE");
    }

    @Test
    @DisplayName("非前置边（包含）反向不算环，应通过校验并落库")
    void containsEdgeReverseIsNotCycle() {
        Course course = course(1L, "C1", "课程一");
        when(courseMapper.selectById(1L)).thenReturn(course);
        when(nodeMapper.selectList(any())).thenReturn(List.of());
        when(edgeMapper.selectList(any())).thenReturn(List.of());
        when(nodeMapper.insert(any(KgNode.class))).thenAnswer(inv -> {
            KgNode n = inv.getArgument(0);
            n.setId("KT01".equals(n.getNodeCode()) ? 10L : 11L);
            return 1;
        });
        GraphImportRequest req = req(
                List.of(node("KT01", "A"), node("KT02", "B")),
                List.of(edge("KT01", "KT02", "前置"), edge("KT02", "KT01", "包含")));

        GraphImportResult result = service().importGraph("C1", req);

        assertThat(result.getEdgeCount()).isEqualTo(2);
        verify(nodeMapper, never()).delete(any());
    }

    // ───────── 差量对账：保主键 / 归档 / 幂等 ─────────

    @Test
    @DisplayName("同 node_code 重导入保留 kg_node.id，更新可变字段，不 delete")
    void reimportSameNodeCodeKeepsId() {
        Course course = course(1L, "C1", "课程一");
        when(courseMapper.selectById(1L)).thenReturn(course);

        KgNode existing = kgNode(10L, "KT01", "旧名", true, 4);
        existing.setCourseId(1L);
        when(nodeMapper.selectList(any())).thenReturn(List.of(existing));
        when(edgeMapper.selectList(any())).thenReturn(List.of());
        when(nodeMapper.update(any(), any())).thenReturn(1);

        GraphImportRequest.NodeItem updated = node("KT01", "新名");
        updated.setDifficulty(5);
        GraphImportRequest req = req(List.of(updated), List.of());

        GraphImportResult result = service().importGraph("C1", req);

        assertThat(result.getNodeCount()).isEqualTo(1);
        verify(nodeMapper, never()).insert(any(KgNode.class));
        verify(nodeMapper, never()).delete(any());
        verify(nodeMapper, never()).deleteById(any(Long.class));
        verify(nodeMapper).update(any(), any());
    }

    @Test
    @DisplayName("导入移除的节点被归档而非硬删")
    void removedNodesAreArchivedNotDeleted() {
        Course course = course(1L, "C1", "课程一");
        when(courseMapper.selectById(1L)).thenReturn(course);

        KgNode keep = kgNode(10L, "KT01", "A", false, 2);
        keep.setCourseId(1L);
        KgNode drop = kgNode(11L, "KT02", "B", false, 2);
        drop.setCourseId(1L);
        when(nodeMapper.selectList(any())).thenReturn(List.of(keep, drop));
        when(edgeMapper.selectList(any())).thenReturn(List.of());
        when(nodeMapper.update(any(), any())).thenReturn(1);
        when(nodeMapper.updateById(any(KgNode.class))).thenReturn(1);

        GraphImportRequest req = req(List.of(node("KT01", "A-new")), List.of());
        service().importGraph("C1", req);

        ArgumentCaptor<KgNode> archiveCap = ArgumentCaptor.forClass(KgNode.class);
        verify(nodeMapper).updateById(archiveCap.capture());
        assertThat(archiveCap.getValue().getId()).isEqualTo(11L);
        assertThat(archiveCap.getValue().getArchivedAt()).isNotNull();
        verify(nodeMapper, never()).delete(any());
        verify(nodeMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("已归档节点在重导入时解除归档（清 archived_at）")
    void reappearingArchivedNodeIsRestored() {
        Course course = course(1L, "C1", "课程一");
        when(courseMapper.selectById(1L)).thenReturn(course);

        KgNode archived = kgNode(10L, "KT01", "旧", false, 1);
        archived.setCourseId(1L);
        archived.setArchivedAt(LocalDateTime.now().minusDays(1));
        when(nodeMapper.selectList(any())).thenReturn(List.of(archived));
        when(edgeMapper.selectList(any())).thenReturn(List.of());
        when(nodeMapper.update(any(), any())).thenReturn(1);

        service().importGraph("C1", req(List.of(node("KT01", "恢复")), List.of()));

        verify(nodeMapper).update(any(), any());
        verify(nodeMapper, never()).insert(any(KgNode.class));
    }

    @Test
    @DisplayName("边按 (from,to,type) 对账：已有边更新不清删，多余边归档")
    void edgeReconcilePreservesIdentity() {
        Course course = course(1L, "C1", "课程一");
        when(courseMapper.selectById(1L)).thenReturn(course);

        KgNode n1 = kgNode(10L, "KT01", "A", false, 1);
        n1.setCourseId(1L);
        KgNode n2 = kgNode(11L, "KT02", "B", false, 1);
        n2.setCourseId(1L);
        when(nodeMapper.selectList(any())).thenReturn(List.of(n1, n2));
        when(nodeMapper.update(any(), any())).thenReturn(1);

        KgEdge keep = kgEdge(10L, 11L, 1);
        keep.setId(100L);
        keep.setCourseId(1L);
        KgEdge drop = kgEdge(11L, 10L, 3);
        drop.setId(101L);
        drop.setCourseId(1L);
        when(edgeMapper.selectList(any())).thenReturn(List.of(keep, drop));
        when(edgeMapper.update(any(), any())).thenReturn(1);
        when(edgeMapper.updateById(any(KgEdge.class))).thenReturn(1);

        GraphImportRequest req = req(
                List.of(node("KT01", "A"), node("KT02", "B")),
                List.of(edge("KT01", "KT02", "前置")));

        GraphImportResult result = service().importGraph("C1", req);

        assertThat(result.getEdgeCount()).isEqualTo(1);
        verify(edgeMapper, never()).insert(any(KgEdge.class));
        verify(edgeMapper, never()).delete(any());
        ArgumentCaptor<KgEdge> edgeCap = ArgumentCaptor.forClass(KgEdge.class);
        verify(edgeMapper).updateById(edgeCap.capture());
        assertThat(edgeCap.getValue().getId()).isEqualTo(101L);
        assertThat(edgeCap.getValue().getArchivedAt()).isNotNull();
    }

    @Test
    @DisplayName("幂等重导入：相同载荷不 insert 新节点/边")
    void idempotentReimportNoInserts() {
        Course course = course(1L, "C1", "课程一");
        when(courseMapper.selectById(1L)).thenReturn(course);

        KgNode n1 = kgNode(10L, "KT01", "A", false, 3);
        n1.setCourseId(1L);
        KgNode n2 = kgNode(11L, "KT02", "B", false, 3);
        n2.setCourseId(1L);
        when(nodeMapper.selectList(any())).thenReturn(List.of(n1, n2));
        when(nodeMapper.update(any(), any())).thenReturn(1);

        KgEdge e = kgEdge(10L, 11L, 1);
        e.setId(50L);
        e.setCourseId(1L);
        when(edgeMapper.selectList(any())).thenReturn(List.of(e));
        when(edgeMapper.update(any(), any())).thenReturn(1);

        GraphImportRequest req = req(
                List.of(node("KT01", "A"), node("KT02", "B")),
                List.of(edge("KT01", "KT02", "前置")));

        service().importGraph("C1", req);

        verify(nodeMapper, never()).insert(any(KgNode.class));
        verify(edgeMapper, never()).insert(any(KgEdge.class));
        verify(nodeMapper, never()).delete(any());
        verify(edgeMapper, never()).delete(any());
    }

    @Test
    @DisplayName("首导：is_key=true 落库为 1，缺省难度补 1")
    void nodeKeyAndDifficultyMapping() {
        Course course = course(1L, "C1", "课程一");
        when(courseMapper.selectById(1L)).thenReturn(course);
        when(nodeMapper.selectList(any())).thenReturn(List.of());
        when(edgeMapper.selectList(any())).thenReturn(List.of());
        ArgumentCaptor<KgNode> nodeCaptor = ArgumentCaptor.forClass(KgNode.class);
        when(nodeMapper.insert(nodeCaptor.capture())).thenAnswer(inv -> {
            KgNode n = inv.getArgument(0);
            n.setId(1L);
            return 1;
        });

        GraphImportRequest.NodeItem key = node("KT01", "重点");
        key.setIsKey(true);
        key.setDifficulty(null);
        GraphImportRequest req = req(List.of(key), List.of());

        service().importGraph("C1", req);

        KgNode saved = nodeCaptor.getValue();
        assertThat(saved.getIsKey()).isEqualTo(1);
        assertThat(saved.getDifficulty()).isEqualTo(1);
    }

    // ───────── 查询 VO 映射 ─────────

    @Test
    @DisplayName("查询把实体映射为 VO：掌握度统一 unlearned，边端点回填为编码、类型为中文标签")
    void getGraphMapsVo() {
        when(courseMapper.selectById(1L)).thenReturn(course(1L, "C1", "课程一"));
        KgNode n1 = kgNode(10L, "KT01", "用例", true, 4);
        KgNode n2 = kgNode(11L, "KT02", "类图", false, 2);
        when(nodeMapper.selectList(any())).thenReturn(List.of(n1, n2));
        when(edgeMapper.selectList(any())).thenReturn(List.of(kgEdge(10L, 11L, 1)));

        GraphDataVO vo = service().getGraph(1L);

        assertThat(vo.getCourse().getName()).isEqualTo("课程一");
        assertThat(vo.getNodes()).hasSize(2);
        GraphDataVO.NodeVO first = vo.getNodes().get(0);
        assertThat(first.getNodeCode()).isEqualTo("KT01");
        assertThat(first.getIsKey()).isTrue();
        assertThat(first.getMastery()).isEqualTo("unlearned");
        assertThat(vo.getNodes()).allMatch(nv -> "unlearned".equals(nv.getMastery()));

        assertThat(vo.getEdges()).hasSize(1);
        GraphDataVO.EdgeVO e = vo.getEdges().get(0);
        assertThat(e.getSource()).isEqualTo("KT01");
        assertThat(e.getTarget()).isEqualTo("KT02");
        assertThat(e.getType()).isEqualTo("前置");
    }

    @Test
    @DisplayName("归档课程查询抛 NOT_FOUND")
    void getGraphArchivedCourseNotFound() {
        Course c = course(1L, "C1", "课程一");
        c.setArchivedAt(LocalDateTime.now());
        when(courseMapper.selectById(1L)).thenReturn(c);
        assertThatThrownBy(() -> service().getGraph(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("衰减开启：updated_at 回拨 40 天后 effectiveLevel 降级，mastery 染色用有效等级")
    void getGraphAppliesDecayWhenEvidenceStale() {
        when(courseMapper.selectById(1L)).thenReturn(course(1L, "C1", "课程一"));
        KgNode n1 = kgNode(10L, "KT01", "用例", true, 4);
        when(nodeMapper.selectList(any())).thenReturn(List.of(n1));
        when(edgeMapper.selectList(any())).thenReturn(List.of());

        StudentMastery sm = new StudentMastery();
        sm.setNodeId(10L);
        sm.setMasteryScore(new BigDecimal("88.00"));
        sm.setMasteryLevel(2);
        sm.setUpdatedAt(LocalDateTime.now().minusDays(40));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(sm));

        GraphDataVO vo = service().getGraph(1L, 2L);

        GraphDataVO.NodeVO node = vo.getNodes().get(0);
        assertThat(node.getRawScore()).isEqualTo(88.0);
        assertThat(node.getMasteryScore()).isEqualTo(88.0);
        assertThat(node.getEffectiveScore()).isCloseTo(41.6, within(1.0));
        assertThat(node.getEffectiveLevel()).isEqualTo(1);
        assertThat(node.getMastery()).isEqualTo("weak");
        assertThat(node.getDaysSinceEvidence()).isCloseTo(40.0, within(0.5));

        verify(studentMasteryMapper, never()).updateById(any(StudentMastery.class));
        verify(studentMasteryMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("衰减关闭时 effective≡raw，mastery 仍按原始分阈值染色")
    void getGraphDecayDisabled_effectiveEqualsRaw() {
        when(courseMapper.selectById(1L)).thenReturn(course(1L, "C1", "课程一"));
        KgNode n1 = kgNode(10L, "KT01", "用例", true, 4);
        when(nodeMapper.selectList(any())).thenReturn(List.of(n1));
        when(edgeMapper.selectList(any())).thenReturn(List.of());

        StudentMastery sm = new StudentMastery();
        sm.setNodeId(10L);
        sm.setMasteryScore(new BigDecimal("88.00"));
        sm.setMasteryLevel(2);
        sm.setUpdatedAt(LocalDateTime.now().minusDays(40));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(sm));

        GraphDataVO vo = serviceWithDecay(false, true).getGraph(1L, 2L);

        GraphDataVO.NodeVO node = vo.getNodes().get(0);
        assertThat(node.getRawScore()).isEqualTo(88.0);
        assertThat(node.getEffectiveScore()).isEqualTo(88.0);
        assertThat(node.getEffectiveLevel()).isEqualTo(2);
        assertThat(node.getMastery()).isEqualTo("mastered");

        verify(studentMasteryMapper, never()).updateById(any(StudentMastery.class));
        verify(studentMasteryMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("带 studentId：有掌握行的节点填真实级别+分值，无行的节点回退 unlearned/null")
    void getGraphWithStudentFillsMastery() {
        when(courseMapper.selectById(1L)).thenReturn(course(1L, "C1", "课程一"));
        KgNode n1 = kgNode(10L, "KT01", "用例", true, 4);
        KgNode n2 = kgNode(11L, "KT02", "类图", false, 2);
        when(nodeMapper.selectList(any())).thenReturn(List.of(n1, n2));
        when(edgeMapper.selectList(any())).thenReturn(List.of());

        StudentMastery sm = new StudentMastery();
        sm.setNodeId(10L);
        sm.setMasteryScore(new BigDecimal("80.00"));
        sm.setMasteryLevel(2);
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(sm));

        GraphDataVO vo = service().getGraph(1L, 2L);

        GraphDataVO.NodeVO first = vo.getNodes().get(0);
        assertThat(first.getNodeCode()).isEqualTo("KT01");
        assertThat(first.getMastery()).isEqualTo("mastered");
        assertThat(first.getMasteryScore()).isEqualTo(80.0);

        GraphDataVO.NodeVO second = vo.getNodes().get(1);
        assertThat(second.getMastery()).isEqualTo("unlearned");
        assertThat(second.getMasteryScore()).isNull();
    }

    @Test
    @DisplayName("不带 studentId：节点全 unlearned、分值 null，不查 student_mastery")
    void getGraphWithoutStudentAllUnlearned() {
        when(courseMapper.selectById(1L)).thenReturn(course(1L, "C1", "课程一"));
        when(nodeMapper.selectList(any())).thenReturn(List.of(kgNode(10L, "KT01", "用例", true, 4)));
        when(edgeMapper.selectList(any())).thenReturn(List.of());

        GraphDataVO vo = service().getGraph(1L, null);

        assertThat(vo.getNodes()).allMatch(nv -> "unlearned".equals(nv.getMastery()));
        assertThat(vo.getNodes().get(0).getMasteryScore()).isNull();
        verifyNoInteractions(studentMasteryMapper);
    }

    @Test
    @DisplayName("查询不存在的课程抛 NOT_FOUND")
    void getGraphUnknownCourse() {
        when(courseMapper.selectById(any())).thenReturn(null);
        assertThatThrownBy(() -> service().getGraph(999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
    }

    // ───────── 测试辅助 ─────────

    private GraphValidateResult expectValidateFail(GraphImportRequest req) {
        try {
            service().importGraph("C1", req);
            throw new AssertionError("应当抛出校验异常但未抛出");
        } catch (BusinessException ex) {
            assertThat(ex.getCode()).isEqualTo(ResultCode.GRAPH_VALIDATE_FAIL.getCode());
            assertThat(ex.getDetail()).isInstanceOf(GraphValidateResult.class);
            return (GraphValidateResult) ex.getDetail();
        }
    }

    private List<String> categories(GraphValidateResult vr) {
        return vr.getIssues().stream().map(GraphValidateResult.Issue::getCategory).toList();
    }

    private List<String> messages(GraphValidateResult vr) {
        return vr.getIssues().stream().map(GraphValidateResult.Issue::getMessage).toList();
    }

    private GraphImportRequest req(List<GraphImportRequest.NodeItem> nodes,
                                   List<GraphImportRequest.EdgeItem> edges) {
        GraphImportRequest r = new GraphImportRequest();
        r.setNodes(new ArrayList<>(nodes));
        r.setEdges(new ArrayList<>(edges));
        return r;
    }

    private GraphImportRequest.NodeItem node(String id, String name) {
        GraphImportRequest.NodeItem n = new GraphImportRequest.NodeItem();
        n.setId(id);
        n.setName(name);
        n.setChapter("章");
        n.setDifficulty(3);
        n.setIsKey(false);
        return n;
    }

    private GraphImportRequest.EdgeItem edge(String src, String tgt, String type) {
        GraphImportRequest.EdgeItem e = new GraphImportRequest.EdgeItem();
        e.setSource(src);
        e.setTarget(tgt);
        e.setType(type);
        return e;
    }

    private Course course(Long id, String code, String name) {
        Course c = new Course();
        c.setId(id);
        c.setCode(code);
        c.setName(name);
        return c;
    }

    private KgNode kgNode(Long id, String code, String name, boolean key, int difficulty) {
        KgNode n = new KgNode();
        n.setId(id);
        n.setNodeCode(code);
        n.setName(name);
        n.setChapter("章");
        n.setDifficulty(difficulty);
        n.setIsKey(key ? 1 : 0);
        n.setDescription("desc");
        return n;
    }

    private KgEdge kgEdge(Long from, Long to, int type) {
        KgEdge e = new KgEdge();
        e.setFromNodeId(from);
        e.setToNodeId(to);
        e.setRelationType(type);
        return e;
    }

    // ─────────── decaying 字段 ───────────

    @Test
    @DisplayName("decaying: 回拨 40 天高分行 → decaying == true")
    void decaying_staleHighScore_isTrue() {
        when(courseMapper.selectById(1L)).thenReturn(course(1L, "C1", "课程一"));
        KgNode n1 = kgNode(10L, "KT01", "用例", true, 4);
        when(nodeMapper.selectList(any())).thenReturn(List.of(n1));
        when(edgeMapper.selectList(any())).thenReturn(List.of());

        StudentMastery sm = new StudentMastery();
        sm.setNodeId(10L);
        sm.setMasteryScore(new BigDecimal("88.00"));
        sm.setMasteryLevel(2);
        sm.setUpdatedAt(LocalDateTime.now().minusDays(40));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(sm));

        GraphDataVO vo = service().getGraph(1L, 2L);

        assertThat(vo.getNodes().get(0).getDecaying()).isTrue();
    }

    @Test
    @DisplayName("decaying: 新鲜高分行（updatedAt=now）→ decaying == false")
    void decaying_freshHighScore_isFalse() {
        when(courseMapper.selectById(1L)).thenReturn(course(1L, "C1", "课程一"));
        KgNode n1 = kgNode(10L, "KT01", "用例", true, 4);
        when(nodeMapper.selectList(any())).thenReturn(List.of(n1));
        when(edgeMapper.selectList(any())).thenReturn(List.of());

        StudentMastery sm = new StudentMastery();
        sm.setNodeId(10L);
        sm.setMasteryScore(new BigDecimal("88.00"));
        sm.setMasteryLevel(2);
        sm.setUpdatedAt(LocalDateTime.now());
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(sm));

        GraphDataVO vo = service().getGraph(1L, 2L);

        assertThat(vo.getNodes().get(0).getDecaying()).isFalse();
    }

    @Test
    @DisplayName("decaying: fuse off（enabled=false）→ decaying == false")
    void decaying_fuseOff_isFalse() {
        when(courseMapper.selectById(1L)).thenReturn(course(1L, "C1", "课程一"));
        KgNode n1 = kgNode(10L, "KT01", "用例", true, 4);
        when(nodeMapper.selectList(any())).thenReturn(List.of(n1));
        when(edgeMapper.selectList(any())).thenReturn(List.of());

        StudentMastery sm = new StudentMastery();
        sm.setNodeId(10L);
        sm.setMasteryScore(new BigDecimal("88.00"));
        sm.setMasteryLevel(2);
        sm.setUpdatedAt(LocalDateTime.now().minusDays(40));
        when(studentMasteryMapper.selectList(any())).thenReturn(List.of(sm));

        GraphDataVO vo = serviceWithDecay(false, true).getGraph(1L, 2L);

        assertThat(vo.getNodes().get(0).getDecaying()).isFalse();
    }

    @Test
    @DisplayName("decaying: 无掌握行的节点 → decaying == null")
    void decaying_noMasteryRow_isNull() {
        when(courseMapper.selectById(1L)).thenReturn(course(1L, "C1", "课程一"));
        KgNode n1 = kgNode(10L, "KT01", "用例", true, 4);
        when(nodeMapper.selectList(any())).thenReturn(List.of(n1));
        when(edgeMapper.selectList(any())).thenReturn(List.of());

        when(studentMasteryMapper.selectList(any())).thenReturn(List.of());

        GraphDataVO vo = service().getGraph(1L, 2L);

        assertThat(vo.getNodes().get(0).getDecaying()).isNull();
    }

    @Test
    @DisplayName("getGraph 过滤待复核边（学生侧不可见）")
    void getGraphExcludesPendingEdges() {
        Course course = course(1L, "C1", "课程一");
        when(courseMapper.selectById(1L)).thenReturn(course);

        KgNode n1 = kgNode(1L, "N1", "节点1", true, 3);
        KgNode n2 = kgNode(2L, "N2", "节点2", false, 2);
        when(nodeMapper.selectList(any())).thenReturn(List.of(n1, n2));

        KgEdge e1 = kgEdge(1L, 2L, 1);
        e1.setRelationNote("『待复核』AI推荐边");

        KgEdge e2 = kgEdge(2L, 1L, 3);
        e2.setRelationNote("正常边描述");

        when(edgeMapper.selectList(any())).thenReturn(List.of(e1, e2));

        GraphDataVO result = service().getGraph(1L, null);

        assertThat(result.getEdges()).hasSize(1);
        assertThat(result.getEdges().get(0).getSource()).isEqualTo("N2");
        assertThat(result.getEdges().get(0).getTarget()).isEqualTo("N1");
        assertThat(result.getEdges().get(0).getType()).isEqualTo("相关");
    }
}
