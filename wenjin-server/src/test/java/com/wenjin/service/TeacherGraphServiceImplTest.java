package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.dto.NodeUpsertRequest;
import com.wenjin.dto.PendingEdgeVO;
import com.wenjin.dto.TeacherGraphVO;
import com.wenjin.entity.Course;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.service.impl.TeacherGraphServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeacherGraphServiceImplTest {

    @Mock
    private KgNodeMapper nodeMapper;

    @Mock
    private KgEdgeMapper edgeMapper;

    @Mock
    private QuestionNodeMapper questionNodeMapper;

    @Mock
    private CourseMapper courseMapper;

    @InjectMocks
    private TeacherGraphServiceImpl service;

    @Test
    void getGraphReturnsFullGraphWithPendingFlag() {
        // Given
        KgNode n1 = new KgNode();
        n1.setId(1L);
        n1.setNodeCode("N1");
        n1.setName("节点1");
        n1.setChapter("第一章");
        n1.setDifficulty(3);
        n1.setIsKey(1);
        n1.setDescription("描述1");
        n1.setNodeNote("备注1");

        KgNode n2 = new KgNode();
        n2.setId(2L);
        n2.setNodeCode("N2");
        n2.setName("节点2");

        KgNode n3 = new KgNode();
        n3.setId(3L);
        n3.setNodeCode("N3");
        n3.setName("节点3");

        KgEdge e1 = new KgEdge();
        e1.setId(10L);
        e1.setFromNodeId(1L);
        e1.setToNodeId(2L);
        e1.setRelationType(1); // 前置
        e1.setRelationNote("『待复核』AI推荐");
        e1.setConfidence(75);

        KgEdge e2 = new KgEdge();
        e2.setId(11L);
        e2.setFromNodeId(2L);
        e2.setToNodeId(3L);
        e2.setRelationType(3); // 相关
        e2.setRelationNote("正常描述");
        e2.setConfidence(90);

        when(nodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(n1, n2, n3));
        when(edgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(e1, e2));

        // When
        TeacherGraphVO result = service.getGraph(1L);

        // Then
        assertThat(result.getNodes()).hasSize(3);
        assertThat(result.getNodes().get(0).getNodeCode()).isEqualTo("N1");

        assertThat(result.getEdges()).hasSize(2);
        TeacherGraphVO.EdgeVO edge1 = result.getEdges().get(0);
        assertThat(edge1.getId()).isEqualTo(10L);
        assertThat(edge1.getSource()).isEqualTo("N1");
        assertThat(edge1.getTarget()).isEqualTo("N2");
        assertThat(edge1.getPending()).isTrue();
        assertThat(edge1.getNote()).isEqualTo("『待复核』AI推荐");

        TeacherGraphVO.EdgeVO edge2 = result.getEdges().get(1);
        assertThat(edge2.getId()).isEqualTo(11L);
        assertThat(edge2.getPending()).isFalse();
    }

    @Test
    void pendingEdgesSortedByConfidenceDesc() {
        // Given
        KgNode n1 = new KgNode();
        n1.setId(1L);
        n1.setNodeCode("N1");
        n1.setName("节点1");
        KgNode n2 = new KgNode();
        n2.setId(2L);
        n2.setNodeCode("N2");
        n2.setName("节点2");
        KgNode n3 = new KgNode();
        n3.setId(3L);
        n3.setNodeCode("N3");
        n3.setName("节点3");

        KgEdge e1 = new KgEdge();
        e1.setId(10L);
        e1.setFromNodeId(1L);
        e1.setToNodeId(2L);
        e1.setRelationType(1); // 前置
        e1.setRelationNote("『待复核』低置信度");
        e1.setConfidence(65);

        KgEdge e2 = new KgEdge();
        e2.setId(11L);
        e2.setFromNodeId(2L);
        e2.setToNodeId(3L);
        e2.setRelationType(1); // 前置
        e2.setRelationNote("正常边");
        e2.setConfidence(90);

        KgEdge e3 = new KgEdge();
        e3.setId(12L);
        e3.setFromNodeId(1L);
        e3.setToNodeId(3L);
        e3.setRelationType(3); // 相关
        e3.setRelationNote("『待复核』高置信度");
        e3.setConfidence(80);

        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(n1, n2, n3));
        when(edgeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(e1, e2, e3));

        // When
        List<PendingEdgeVO> result = service.pendingEdges(1L);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(12L);
        assertThat(result.get(0).getConfidence()).isEqualTo(80);
        assertThat(result.get(0).getReason()).isEqualTo("高置信度");
        assertThat(result.get(0).getLow()).isFalse();
        assertThat(result.get(0).getFromCode()).isEqualTo("N1");
        assertThat(result.get(0).getToCode()).isEqualTo("N3");

        assertThat(result.get(1).getId()).isEqualTo(10L);
        assertThat(result.get(1).getConfidence()).isEqualTo(65);
        assertThat(result.get(1).getReason()).isEqualTo("低置信度");
        assertThat(result.get(1).getLow()).isTrue();
    }

    @Test
    void acceptEdgeStripsPrefix() {
        // Given
        KgEdge edge = new KgEdge();
        edge.setId(10L);
        edge.setRelationNote("『待复核』AI推荐原因");
        when(edgeMapper.selectById(10L)).thenReturn(edge);
        when(edgeMapper.updateById(any(KgEdge.class))).thenReturn(1);

        // When
        service.acceptEdge(10L);

        // Then
        ArgumentCaptor<KgEdge> captor = ArgumentCaptor.forClass(KgEdge.class);
        verify(edgeMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRelationNote()).isEqualTo("AI推荐原因");
    }

    @Test
    void rejectEdgeDeletesRow() {
        // Given
        KgEdge edge = new KgEdge();
        edge.setId(10L);
        when(edgeMapper.selectById(10L)).thenReturn(edge);
        when(edgeMapper.deleteById(10L)).thenReturn(1);

        // When
        service.rejectEdge(10L);

        // Then
        verify(edgeMapper).selectById(10L);
        verify(edgeMapper).deleteById(10L);
    }

    @Test
    void deleteNodeCascadesEdgesAndQuestionNodes() {
        // Given
        KgNode node = new KgNode();
        node.setId(1L);
        node.setNodeCode("N1");
        when(nodeMapper.selectById(1L)).thenReturn(node);
        when(edgeMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(2);
        when(questionNodeMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(3);
        when(nodeMapper.deleteById(1L)).thenReturn(1);

        // When
        service.deleteNode(1L);

        // Then
        // Should delete edges by fromNodeId and toNodeId (2 calls)
        verify(edgeMapper, times(2)).delete(any(LambdaQueryWrapper.class));
        verify(questionNodeMapper).delete(any(LambdaQueryWrapper.class));
        verify(nodeMapper).deleteById(1L);
    }

    @Test
    void createNodeRejectsDuplicateCode() {
        // Given
        Course course = new Course();
        course.setId(1L);
        when(courseMapper.selectById(1L)).thenReturn(course);

        NodeUpsertRequest req = new NodeUpsertRequest();
        req.setNodeCode("N1");
        req.setName("新节点");

        KgNode existing = new KgNode();
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(existing));

        // When/Then
        assertThatThrownBy(() -> service.createNode(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("节点编码已存在");

        verify(nodeMapper, never()).insert(any(KgNode.class));
    }

    @Test
    void createNodeInsertsWhenCodeUnique() {
        // Given
        Course course = new Course();
        course.setId(1L);
        when(courseMapper.selectById(1L)).thenReturn(course);

        NodeUpsertRequest req = new NodeUpsertRequest();
        req.setNodeCode("N1");
        req.setName("新节点");
        req.setChapter("第一章");
        req.setDifficulty(3);
        req.setIsKey(true);
        req.setDescription("描述");
        req.setNote("备注");

        when(nodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList());
        when(nodeMapper.insert(any(KgNode.class))).thenAnswer(invocation -> {
            KgNode node = invocation.getArgument(0);
            node.setId(100L);
            return 1;
        });

        // When
        Long nodeId = service.createNode(1L, req);

        // Then
        assertThat(nodeId).isEqualTo(100L);
        ArgumentCaptor<KgNode> captor = ArgumentCaptor.forClass(KgNode.class);
        verify(nodeMapper).insert(captor.capture());
        assertThat(captor.getValue().getNodeCode()).isEqualTo("N1");
        assertThat(captor.getValue().getCourseId()).isEqualTo(1L);
    }

    @Test
    void updateNodeModifiesFields() {
        // Given
        KgNode existing = new KgNode();
        existing.setId(1L);
        existing.setNodeCode("N1");
        when(nodeMapper.selectById(1L)).thenReturn(existing);
        when(nodeMapper.updateById(any(KgNode.class))).thenReturn(1);

        NodeUpsertRequest req = new NodeUpsertRequest();
        // 不设置 nodeCode，或设置为 null，避免触发修改检查
        req.setName("新名称");
        req.setChapter("新章节");
        req.setDifficulty(5);
        req.setIsKey(false);
        req.setDescription("新描述");
        req.setNote("新备注");

        // When
        service.updateNode(1L, req);

        // Then
        ArgumentCaptor<KgNode> captor = ArgumentCaptor.forClass(KgNode.class);
        verify(nodeMapper).updateById(captor.capture());
        KgNode updated = captor.getValue();
        assertThat(updated.getNodeCode()).isEqualTo("N1");  // nodeCode 保持原值
        assertThat(updated.getName()).isEqualTo("新名称");
        assertThat(updated.getChapter()).isEqualTo("新章节");
        assertThat(updated.getDifficulty()).isEqualTo(5);
        assertThat(updated.getIsKey()).isEqualTo(0);
        assertThat(updated.getDescription()).isEqualTo("新描述");
        assertThat(updated.getNodeNote()).isEqualTo("新备注");
    }

    // ========== 新增测试用例：C1 输入验证 ==========

    @Test
    void getGraphRejectsNullCourseId() {
        assertThatThrownBy(() -> service.getGraph(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("courseId 无效");
    }

    @Test
    void getGraphRejectsInvalidCourseId() {
        assertThatThrownBy(() -> service.getGraph(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("courseId 无效");
    }

    @Test
    void pendingEdgesRejectsNullCourseId() {
        assertThatThrownBy(() -> service.pendingEdges(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("courseId 无效");
    }

    @Test
    void acceptEdgeRejectsNullEdgeId() {
        assertThatThrownBy(() -> service.acceptEdge(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("edgeId 无效");
    }

    @Test
    void rejectEdgeRejectsNullEdgeId() {
        assertThatThrownBy(() -> service.rejectEdge(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("edgeId 无效");
    }

    @Test
    void createNodeRejectsNullCourseId() {
        NodeUpsertRequest req = new NodeUpsertRequest();
        req.setNodeCode("N1");
        assertThatThrownBy(() -> service.createNode(null, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("courseId 无效");
    }

    @Test
    void createNodeRejectsNullRequest() {
        assertThatThrownBy(() -> service.createNode(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请求参数不能为空");
    }

    @Test
    void createNodeRejectsNullNodeCode() {
        NodeUpsertRequest req = new NodeUpsertRequest();
        req.setNodeCode(null);
        assertThatThrownBy(() -> service.createNode(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("节点编码不能为空");
    }

    @Test
    void updateNodeRejectsNullNodeId() {
        NodeUpsertRequest req = new NodeUpsertRequest();
        assertThatThrownBy(() -> service.updateNode(null, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodeId 无效");
    }

    @Test
    void updateNodeRejectsNullRequest() {
        assertThatThrownBy(() -> service.updateNode(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请求参数不能为空");
    }

    @Test
    void deleteNodeRejectsNullNodeId() {
        assertThatThrownBy(() -> service.deleteNode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodeId 无效");
    }

    // ========== 新增测试用例：C2 边存在性检查 ==========

    @Test
    void acceptEdgeRejectsNonexistentEdge() {
        when(edgeMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.acceptEdge(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("边不存在: edgeId=999");
    }

    @Test
    void rejectEdgeRejectsNonexistentEdge() {
        when(edgeMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.rejectEdge(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("边不存在: edgeId=999");
    }

    // ========== 新增测试用例：C3 脏数据处理 ==========

    @Test
    void pendingEdgesSkipsDirtyEdges() {
        // Given: 一条边指向已删除的节点
        KgNode n1 = new KgNode();
        n1.setId(1L);
        n1.setNodeCode("N1");
        n1.setName("节点1");

        KgEdge validEdge = new KgEdge();
        validEdge.setId(10L);
        validEdge.setFromNodeId(1L);
        validEdge.setToNodeId(2L);  // 节点2不存在
        validEdge.setRelationType(1);
        validEdge.setRelationNote("『待复核』孤儿边");
        validEdge.setConfidence(70);

        KgEdge validEdge2 = new KgEdge();
        validEdge2.setId(11L);
        validEdge2.setFromNodeId(1L);
        validEdge2.setToNodeId(1L);  // 指向自己，有效
        validEdge2.setRelationType(1);
        validEdge2.setRelationNote("『待复核』自环边");
        validEdge2.setConfidence(80);

        when(nodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(n1));
        when(edgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(validEdge, validEdge2));

        // When
        List<PendingEdgeVO> result = service.pendingEdges(1L);

        // Then: 只返回有效边
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(11L);
        assertThat(result.get(0).getFromCode()).isEqualTo("N1");
        assertThat(result.get(0).getToCode()).isEqualTo("N1");
    }

    // ========== 新增测试用例：I5 课程存在性校验 ==========

    @Test
    void createNodeRejectsNonexistentCourse() {
        NodeUpsertRequest req = new NodeUpsertRequest();
        req.setNodeCode("N1");
        req.setName("新节点");

        when(courseMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.createNode(999L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("课程不存在: courseId=999");
    }

    @Test
    void createNodeSucceedsWhenCourseExists() {
        // Given
        Course course = new Course();
        course.setId(1L);
        when(courseMapper.selectById(1L)).thenReturn(course);

        NodeUpsertRequest req = new NodeUpsertRequest();
        req.setNodeCode("N1");
        req.setName("新节点");

        when(nodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList());
        when(nodeMapper.insert(any(KgNode.class))).thenAnswer(invocation -> {
            KgNode node = invocation.getArgument(0);
            node.setId(100L);
            return 1;
        });

        // When
        Long nodeId = service.createNode(1L, req);

        // Then
        assertThat(nodeId).isEqualTo(100L);
        verify(courseMapper).selectById(1L);
    }

    // ========== 新增测试用例：I6 拒绝修改 nodeCode ==========

    @Test
    void updateNodeRejectsNodeCodeChange() {
        // Given
        KgNode existing = new KgNode();
        existing.setId(1L);
        existing.setNodeCode("N1");
        when(nodeMapper.selectById(1L)).thenReturn(existing);

        NodeUpsertRequest req = new NodeUpsertRequest();
        req.setNodeCode("N2");  // 尝试修改 nodeCode

        // When/Then
        assertThatThrownBy(() -> service.updateNode(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("节点编码不可修改: nodeCode=N1");

        verify(nodeMapper, never()).updateById(any(KgNode.class));
    }

    @Test
    void updateNodeAllowsSameNodeCode() {
        // Given
        KgNode existing = new KgNode();
        existing.setId(1L);
        existing.setNodeCode("N1");
        when(nodeMapper.selectById(1L)).thenReturn(existing);
        when(nodeMapper.updateById(any(KgNode.class))).thenReturn(1);

        NodeUpsertRequest req = new NodeUpsertRequest();
        req.setNodeCode("N1");  // 相同的 nodeCode 应该允许
        req.setName("更新名称");

        // When
        service.updateNode(1L, req);

        // Then
        verify(nodeMapper).updateById(any(KgNode.class));
    }
}
