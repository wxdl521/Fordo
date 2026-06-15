package com.wenjin.service;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
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
import com.wenjin.service.impl.GraphServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GraphServiceImpl 单元测试：用 Mockito 隔离数据库依赖，覆盖
 * 校验（重复ID / 孤立边 / 非法关系类型 / 前置环）、导入全量替换映射、查询 VO 映射。
 */
@ExtendWith(MockitoExtension.class)
class GraphServiceImplTest {

    @Mock CourseMapper courseMapper;
    @Mock KgNodeMapper nodeMapper;
    @Mock KgEdgeMapper edgeMapper;
    @Mock StudentMasteryMapper studentMasteryMapper;

    private GraphServiceImpl service() {
        return new GraphServiceImpl(courseMapper, nodeMapper, edgeMapper, studentMasteryMapper);
    }

    // ───────── 校验类（校验先于落库，无需 stub mapper） ─────────

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
    @DisplayName("多节点多类型边的合法 DAG 不报环，整图落库")
    void validDagWithMixedEdgesImports() {
        Course course = course(1L, "C1", "课程一");
        when(courseMapper.selectOne(any())).thenReturn(course);
        when(nodeMapper.insert(any(KgNode.class))).thenAnswer(inv -> {
            KgNode n = inv.getArgument(0);
            n.setId(Long.parseLong(n.getNodeCode().substring(2))); // KT01->1, KT02->2, KT03->3
            return 1;
        });
        // 链：KT01->KT02->KT03 前置（合法 DAG）；KT01->KT03 相关；KT02 包含 KT03
        GraphImportRequest req = req(
                List.of(node("KT01", "A"), node("KT02", "B"), node("KT03", "C")),
                List.of(edge("KT01", "KT02", "前置"),
                        edge("KT02", "KT03", "前置"),
                        edge("KT01", "KT03", "相关"),
                        edge("KT02", "KT03", "包含")));

        GraphImportResult result = service().importGraph("C1", req);

        assertThat(result.getNodeCount()).isEqualTo(3);
        assertThat(result.getEdgeCount()).isEqualTo(4);
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
        when(courseMapper.selectOne(any())).thenReturn(course);
        when(nodeMapper.insert(any(KgNode.class))).thenAnswer(inv -> {
            KgNode n = inv.getArgument(0);
            n.setId("KT01".equals(n.getNodeCode()) ? 10L : 11L);
            return 1;
        });
        // KT01->KT02 前置；KT02->KT01 包含（包含不参与环检测）
        GraphImportRequest req = req(
                List.of(node("KT01", "A"), node("KT02", "B")),
                List.of(edge("KT01", "KT02", "前置"), edge("KT02", "KT01", "包含")));

        GraphImportResult result = service().importGraph("C1", req);

        assertThat(result.getEdgeCount()).isEqualTo(2);
    }

    // ───────── 导入落库：全量替换 + 编码→主键映射 + 关系类型编码 ─────────

    @Test
    @DisplayName("导入对已存在课程先删边再删点（全量替换），并正确映射边端点与关系编码")
    void importFullReplaceAndMapping() {
        Course course = course(1L, "C1", "课程一");
        when(courseMapper.selectOne(any())).thenReturn(course);
        when(nodeMapper.insert(any(KgNode.class))).thenAnswer(inv -> {
            KgNode n = inv.getArgument(0);
            n.setId("KT01".equals(n.getNodeCode()) ? 10L : 11L);
            return 1;
        });

        GraphImportRequest req = req(
                List.of(node("KT01", "A"), node("KT02", "B")),
                List.of(edge("KT01", "KT02", "前置")));

        GraphImportResult result = service().importGraph("C1", req);

        // 结果摘要
        assertThat(result.getCourseId()).isEqualTo(1L);
        assertThat(result.getNodeCount()).isEqualTo(2);
        assertThat(result.getEdgeCount()).isEqualTo(1);

        // 全量替换：边、点各删一次
        verify(edgeMapper, times(1)).delete(any());
        verify(nodeMapper, times(1)).delete(any());

        // 边映射：from=KT01(10) to=KT02(11) 关系=前置(1) 课程=1
        ArgumentCaptor<KgEdge> edgeCaptor = ArgumentCaptor.forClass(KgEdge.class);
        verify(edgeMapper, times(1)).insert(edgeCaptor.capture());
        KgEdge saved = edgeCaptor.getValue();
        assertThat(saved.getFromNodeId()).isEqualTo(10L);
        assertThat(saved.getToNodeId()).isEqualTo(11L);
        assertThat(saved.getRelationType()).isEqualTo(1);
        assertThat(saved.getCourseId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("is_key=true 的节点落库为 1，缺省难度补 1")
    void nodeKeyAndDifficultyMapping() {
        Course course = course(1L, "C1", "课程一");
        when(courseMapper.selectOne(any())).thenReturn(course);
        ArgumentCaptor<KgNode> nodeCaptor = ArgumentCaptor.forClass(KgNode.class);
        when(nodeMapper.insert(nodeCaptor.capture())).thenReturn(1);

        GraphImportRequest.NodeItem key = node("KT01", "重点");
        key.setIsKey(true);
        key.setDifficulty(null); // 缺省难度
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

        GraphDataVO.NodeVO first = vo.getNodes().get(0);   // KT01 有掌握行
        assertThat(first.getNodeCode()).isEqualTo("KT01");
        assertThat(first.getMastery()).isEqualTo("mastered");
        assertThat(first.getMasteryScore()).isEqualTo(80.0);

        GraphDataVO.NodeVO second = vo.getNodes().get(1);  // KT02 无掌握行
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
}
