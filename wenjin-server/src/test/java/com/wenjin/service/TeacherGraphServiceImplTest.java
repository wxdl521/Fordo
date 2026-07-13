package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.EdgeCreateRequest;
import com.wenjin.dto.NodeUpsertRequest;
import com.wenjin.dto.PendingEdgeVO;
import com.wenjin.dto.TeacherGraphVO;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.security.CourseMutationLock;
import com.wenjin.service.impl.TeacherGraphServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeacherGraphServiceImplTest {

    @Mock
    private KgNodeMapper nodeMapper;

    @Mock
    private KgEdgeMapper edgeMapper;

    @Mock
    private CourseAccessService courseAccessService;

    @Mock
    private CourseMutationLock courseMutationLock;

    private FeatureFlagsProperties featureFlags;
    private TeacherGraphServiceImpl service;

    @BeforeEach
    void setUp() {
        featureFlags = new FeatureFlagsProperties();
        featureFlags.setGraphReconcileEnabled(true);
        service = new TeacherGraphServiceImpl(nodeMapper, edgeMapper, courseAccessService,
                courseMutationLock, featureFlags);
        lenient().doNothing().when(courseAccessService).assertManageCourse(anyLong());
        lenient().when(courseAccessService.assertManageNode(anyLong())).thenReturn(1L);
        lenient().when(courseAccessService.assertManageEdge(anyLong())).thenReturn(1L);
    }

    @Test
    void everyMutation_featureDisabled_rejectsBeforeOwnershipLockReadsOrWrites() {
        featureFlags.setGraphReconcileEnabled(false);
        NodeUpsertRequest nodeRequest = new NodeUpsertRequest();
        nodeRequest.setNodeCode("N1");
        nodeRequest.setName("N1");

        assertThatThrownBy(() -> service.acceptEdge(10L)).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
        assertThatThrownBy(() -> service.rejectEdge(10L)).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
        assertThatThrownBy(() -> service.createEdge(1L, edgeReq("N1", "N2", 1)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
        assertThatThrownBy(() -> service.createNode(1L, nodeRequest))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
        assertThatThrownBy(() -> service.updateNode(1L, nodeRequest))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
        assertThatThrownBy(() -> service.deleteNode(1L)).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());

        verifyNoInteractions(courseAccessService, courseMutationLock, nodeMapper, edgeMapper);
    }

    @Test
    void getGraphReturnsFullGraphWithPendingFlag() {
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
        e1.setRelationType(1);
        e1.setRelationNote("『待复核』AI推荐");
        e1.setConfidence(75);

        KgEdge e2 = new KgEdge();
        e2.setId(11L);
        e2.setFromNodeId(2L);
        e2.setToNodeId(3L);
        e2.setRelationType(3);
        e2.setRelationNote("正常描述");
        e2.setConfidence(90);

        when(nodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(n1, n2, n3));
        when(edgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(e1, e2));

        TeacherGraphVO result = service.getGraph(1L);

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
        e1.setRelationType(1);
        e1.setRelationNote("『待复核』低置信度");
        e1.setConfidence(65);

        KgEdge e2 = new KgEdge();
        e2.setId(11L);
        e2.setFromNodeId(2L);
        e2.setToNodeId(3L);
        e2.setRelationType(1);
        e2.setRelationNote("正常边");
        e2.setConfidence(90);

        KgEdge e3 = new KgEdge();
        e3.setId(12L);
        e3.setFromNodeId(1L);
        e3.setToNodeId(3L);
        e3.setRelationType(3);
        e3.setRelationNote("『待复核』高置信度");
        e3.setConfidence(80);

        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(n1, n2, n3));
        when(edgeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(e1, e2, e3));

        List<PendingEdgeVO> result = service.pendingEdges(1L);

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
        KgEdge edge = new KgEdge();
        edge.setId(10L);
        edge.setRelationNote("『待复核』AI推荐原因");
        when(edgeMapper.selectById(10L)).thenReturn(edge);
        when(edgeMapper.updateById(any(KgEdge.class))).thenReturn(1);

        service.acceptEdge(10L);

        InOrder order = inOrder(courseAccessService, courseMutationLock, edgeMapper);
        order.verify(courseAccessService).assertManageEdge(10L);
        order.verify(courseMutationLock).lockActive(1L);
        order.verify(courseAccessService).assertManageEdge(10L);
        order.verify(edgeMapper).selectById(10L);
        order.verify(edgeMapper).updateById(any(KgEdge.class));

        ArgumentCaptor<KgEdge> captor = ArgumentCaptor.forClass(KgEdge.class);
        verify(edgeMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRelationNote()).isEqualTo("AI推荐原因");
    }

    @Test
    void rejectEdgeArchivesRow() {
        KgEdge edge = new KgEdge();
        edge.setId(10L);
        when(edgeMapper.selectById(10L)).thenReturn(edge);
        when(edgeMapper.updateById(any(KgEdge.class))).thenReturn(1);

        service.rejectEdge(10L);

        InOrder order = inOrder(courseAccessService, courseMutationLock, edgeMapper);
        order.verify(courseAccessService).assertManageEdge(10L);
        order.verify(courseMutationLock).lockActive(1L);
        order.verify(courseAccessService).assertManageEdge(10L);
        order.verify(edgeMapper).selectById(10L);
        order.verify(edgeMapper).updateById(any(KgEdge.class));

        ArgumentCaptor<KgEdge> captor = ArgumentCaptor.forClass(KgEdge.class);
        verify(edgeMapper).updateById(captor.capture());
        assertThat(captor.getValue().getArchivedAt()).isNotNull();
        verify(edgeMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteNodeArchivesNodeAndEdges_noHardDeleteOfQuestionNodes() {
        KgNode node = new KgNode();
        node.setId(1L);
        node.setNodeCode("N1");
        when(nodeMapper.selectById(1L)).thenReturn(node);
        when(edgeMapper.update(any(), any())).thenReturn(2);
        when(nodeMapper.updateById(any(KgNode.class))).thenReturn(1);

        service.deleteNode(1L);

        InOrder order = inOrder(courseAccessService, courseMutationLock, nodeMapper, edgeMapper);
        order.verify(courseAccessService).assertManageNode(1L);
        order.verify(courseMutationLock).lockActive(1L);
        order.verify(courseAccessService).assertManageNode(1L);
        order.verify(nodeMapper).selectById(1L);
        order.verify(edgeMapper).update(any(), any());
        order.verify(nodeMapper).updateById(any(KgNode.class));

        verify(edgeMapper).update(any(), any());
        ArgumentCaptor<KgNode> captor = ArgumentCaptor.forClass(KgNode.class);
        verify(nodeMapper).updateById(captor.capture());
        assertThat(captor.getValue().getArchivedAt()).isNotNull();
        verify(nodeMapper, never()).deleteById(any(Long.class));
        verify(edgeMapper, never()).delete(any());
    }

    @Test
    void deleteNode_featureDisabled_503() {
        featureFlags.setGraphReconcileEnabled(false);
        assertThatThrownBy(() -> service.deleteNode(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.SERVICE_UNAVAILABLE.getCode());
        verify(nodeMapper, never()).updateById(any(KgNode.class));
    }

    @Test
    void createNodeRejectsDuplicateCode() {
        NodeUpsertRequest req = new NodeUpsertRequest();
        req.setNodeCode("N1");
        req.setName("新节点");

        KgNode existing = new KgNode();
        existing.setId(50L);
        existing.setNodeCode("N1");
        // archivedAt == null → 生效节点，拒绝重复编码
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(existing));

        assertThatThrownBy(() -> service.createNode(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("节点编码已存在");

        verify(nodeMapper, never()).insert(any(KgNode.class));
        verify(nodeMapper, never()).update(any(), any());
    }

    @Test
    void createNodeRestoresArchivedNodeWithSameCode() {
        NodeUpsertRequest req = new NodeUpsertRequest();
        req.setNodeCode("N1");
        req.setName("恢复节点");
        req.setChapter("第二章");
        req.setDifficulty(2);
        req.setIsKey(false);
        req.setDescription("恢复后描述");

        KgNode archived = new KgNode();
        archived.setId(77L);
        archived.setCourseId(1L);
        archived.setNodeCode("N1");
        archived.setName("旧名");
        archived.setArchivedAt(java.time.LocalDateTime.now());
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(archived));
        when(nodeMapper.update(any(), any())).thenReturn(1);

        Long nodeId = service.createNode(1L, req);

        assertThat(nodeId).isEqualTo(77L);
        verify(nodeMapper, never()).insert(any(KgNode.class));
        verify(nodeMapper).update(any(), any());
    }

    @Test
    void createNodeInsertsWhenCodeUnique() {
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

        Long nodeId = service.createNode(1L, req);

        assertThat(nodeId).isEqualTo(100L);
        ArgumentCaptor<KgNode> captor = ArgumentCaptor.forClass(KgNode.class);
        verify(nodeMapper).insert(captor.capture());
        assertThat(captor.getValue().getNodeCode()).isEqualTo("N1");
        assertThat(captor.getValue().getCourseId()).isEqualTo(1L);
    }

    @Test
    void teacherCannotAcceptEdgeFromAnotherTeachersCourse() {
        doThrow(new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage()))
                .when(courseAccessService).assertManageEdge(900L);

        assertThatThrownBy(() -> service.acceptEdge(900L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(404);
        verify(edgeMapper, never()).updateById(any(KgEdge.class));
    }

    @Test
    void updateNodeModifiesFields() {
        KgNode existing = new KgNode();
        existing.setId(1L);
        existing.setNodeCode("N1");
        when(nodeMapper.selectById(1L)).thenReturn(existing);
        when(nodeMapper.updateById(any(KgNode.class))).thenReturn(1);

        NodeUpsertRequest req = new NodeUpsertRequest();
        req.setName("新名称");
        req.setChapter("新章节");
        req.setDifficulty(5);
        req.setIsKey(false);
        req.setDescription("新描述");
        req.setNote("新备注");

        service.updateNode(1L, req);

        ArgumentCaptor<KgNode> captor = ArgumentCaptor.forClass(KgNode.class);
        verify(nodeMapper).updateById(captor.capture());
        KgNode updated = captor.getValue();
        assertThat(updated.getNodeCode()).isEqualTo("N1");
        assertThat(updated.getName()).isEqualTo("新名称");
        assertThat(updated.getChapter()).isEqualTo("新章节");
        assertThat(updated.getDifficulty()).isEqualTo(5);
        assertThat(updated.getIsKey()).isEqualTo(0);
        assertThat(updated.getDescription()).isEqualTo("新描述");
        assertThat(updated.getNodeNote()).isEqualTo("新备注");
    }

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

    @Test
    void pendingEdgesSkipsDirtyEdges() {
        KgNode n1 = new KgNode();
        n1.setId(1L);
        n1.setNodeCode("N1");
        n1.setName("节点1");

        KgEdge validEdge = new KgEdge();
        validEdge.setId(10L);
        validEdge.setFromNodeId(1L);
        validEdge.setToNodeId(2L);
        validEdge.setRelationType(1);
        validEdge.setRelationNote("『待复核』孤儿边");
        validEdge.setConfidence(70);

        KgEdge validEdge2 = new KgEdge();
        validEdge2.setId(11L);
        validEdge2.setFromNodeId(1L);
        validEdge2.setToNodeId(1L);
        validEdge2.setRelationType(1);
        validEdge2.setRelationNote("『待复核』自环边");
        validEdge2.setConfidence(80);

        when(nodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(n1));
        when(edgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(validEdge, validEdge2));

        List<PendingEdgeVO> result = service.pendingEdges(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(11L);
        assertThat(result.get(0).getFromCode()).isEqualTo("N1");
        assertThat(result.get(0).getToCode()).isEqualTo("N1");
    }

    @Test
    void createNodeRejectsNonexistentCourse() {
        NodeUpsertRequest req = new NodeUpsertRequest();
        req.setNodeCode("N1");
        req.setName("新节点");

        doThrow(new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage()))
                .when(courseAccessService).assertManageCourse(999L);

        assertThatThrownBy(() -> service.createNode(999L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(404);
        verify(nodeMapper, never()).insert(any(KgNode.class));
        verifyNoInteractions(courseMutationLock);
    }

    @Test
    void createNodeSucceedsWhenCourseExists() {
        NodeUpsertRequest req = new NodeUpsertRequest();
        req.setNodeCode("N1");
        req.setName("新节点");

        when(nodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList());
        when(nodeMapper.insert(any(KgNode.class))).thenAnswer(invocation -> {
            KgNode node = invocation.getArgument(0);
            node.setId(100L);
            return 1;
        });

        Long nodeId = service.createNode(1L, req);

        assertThat(nodeId).isEqualTo(100L);
        verify(courseAccessService, times(2)).assertManageCourse(1L);
    }

    @Test
    void updateNodeRejectsNodeCodeChange() {
        KgNode existing = new KgNode();
        existing.setId(1L);
        existing.setNodeCode("N1");
        when(nodeMapper.selectById(1L)).thenReturn(existing);

        NodeUpsertRequest req = new NodeUpsertRequest();
        req.setNodeCode("N2");

        assertThatThrownBy(() -> service.updateNode(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("节点编码不可修改: nodeCode=N1");

        verify(nodeMapper, never()).updateById(any(KgNode.class));
    }

    @Test
    void updateNodeAllowsSameNodeCode() {
        KgNode existing = new KgNode();
        existing.setId(1L);
        existing.setNodeCode("N1");
        when(nodeMapper.selectById(1L)).thenReturn(existing);
        when(nodeMapper.updateById(any(KgNode.class))).thenReturn(1);

        NodeUpsertRequest req = new NodeUpsertRequest();
        req.setNodeCode("N1");
        req.setName("更新名称");

        service.updateNode(1L, req);

        verify(nodeMapper).updateById(any(KgNode.class));
    }

    // ── createEdge ──

    private static EdgeCreateRequest edgeReq(String src, String tgt, int type) {
        EdgeCreateRequest req = new EdgeCreateRequest();
        req.setSourceNodeCode(src);
        req.setTargetNodeCode(tgt);
        req.setRelationType(type);
        return req;
    }

    private static KgNode node(long id, String code) {
        KgNode n = new KgNode();
        n.setId(id);
        n.setNodeCode(code);
        n.setCourseId(1L);
        return n;
    }

    @Test
    void createEdge_foreignCourse_404() {
        doThrow(new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage()))
                .when(courseAccessService).assertManageCourse(999L);

        assertThatThrownBy(() -> service.createEdge(999L, edgeReq("N1", "N2", 1)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(404);

        verify(edgeMapper, never()).insert(any(KgEdge.class));
        verify(nodeMapper, never()).selectList(any());
        verifyNoInteractions(courseMutationLock);
    }

    @Test
    void createEdge_sourceOrTargetMissingInCourse_404() {
        // 起点在本课不存在（异课程节点码 / 已归档）
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList())  // source miss
                .thenReturn(Collections.singletonList(node(2L, "N2"))); // would be target

        assertThatThrownBy(() -> service.createEdge(1L, edgeReq("N1", "N2", 3)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(404);
                    assertThat(be.getMessage()).contains("起点节点不存在");
                });
        verify(edgeMapper, never()).insert(any(KgEdge.class));
    }

    @Test
    void createEdge_targetMissingInCourse_404() {
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(node(1L, "N1")))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.createEdge(1L, edgeReq("N1", "NX", 2)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(404);
                    assertThat(be.getMessage()).contains("终点节点不存在");
                });
        verify(edgeMapper, never()).insert(any(KgEdge.class));
    }

    @Test
    void createEdge_selfLoop_400() {
        assertThatThrownBy(() -> service.createEdge(1L, edgeReq("N1", "N1", 1)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(400);
                    assertThat(be.getMessage()).contains("自环");
                });
        verify(edgeMapper, never()).insert(any(KgEdge.class));
        verify(courseAccessService, times(2)).assertManageCourse(1L);
    }

    @Test
    void createEdge_duplicateActiveEdge_409() {
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(node(1L, "N1")))
                .thenReturn(Collections.singletonList(node(2L, "N2")));
        when(edgeMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> service.createEdge(1L, edgeReq("N1", "N2", 3)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getMessage()).contains("边已存在");
                });
        verify(edgeMapper, never()).insert(any(KgEdge.class));
    }

    @Test
    void createEdge_prerequisiteCycle_409() {
        // 既有 N1→N2 前置，再加 N2→N1 成环
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(node(1L, "N1")))  // source resolve
                .thenReturn(Collections.singletonList(node(2L, "N2")))  // target resolve
                .thenReturn(Arrays.asList(node(1L, "N1"), node(2L, "N2"))); // build adj nodes
        when(edgeMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        KgEdge existing = new KgEdge();
        existing.setFromNodeId(1L);
        existing.setToNodeId(2L);
        existing.setRelationType(1);
        existing.setRelationNote("accepted");
        when(edgeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(existing));

        assertThatThrownBy(() -> service.createEdge(1L, edgeReq("N2", "N1", 1)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getMessage()).contains("环路");
                });
        verify(edgeMapper, never()).insert(any(KgEdge.class));
    }

    @Test
    void createEdge_pendingReversePrerequisiteDoesNotBlockManualEdge() {
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(node(1L, "A")))
                .thenReturn(Collections.singletonList(node(2L, "B")))
                .thenReturn(Arrays.asList(node(1L, "A"), node(2L, "B")));
        when(edgeMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(edgeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(pendingPrerequisite(99L, 2L, 1L)));
        when(edgeMapper.insert(any(KgEdge.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, KgEdge.class).setId(77L);
            return 1;
        });

        assertThat(service.createEdge(1L, edgeReq("A", "B", 1))).isEqualTo(77L);
        verify(edgeMapper).insert(any(KgEdge.class));
    }

    @Test
    void createEdge_duplicateKeyException_409() {
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(node(1L, "N1")))
                .thenReturn(Collections.singletonList(node(2L, "N2")));
        when(edgeMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(edgeMapper.insert(any(KgEdge.class))).thenThrow(new DuplicateKeyException("uk_edge"));

        assertThatThrownBy(() -> service.createEdge(1L, edgeReq("N1", "N2", 3)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(409);
    }

    @Test
    void createEdge_legalEdgeInserts() {
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(node(1L, "N1")))
                .thenReturn(Collections.singletonList(node(2L, "N2")))
                .thenReturn(Arrays.asList(node(1L, "N1"), node(2L, "N2")));
        when(edgeMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(edgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        when(edgeMapper.insert(any(KgEdge.class))).thenAnswer(inv -> {
            KgEdge e = inv.getArgument(0);
            e.setId(55L);
            return 1;
        });

        EdgeCreateRequest req = edgeReq("N1", "N2", 1);
        req.setRelationNote("教师手动画边");
        Long id = service.createEdge(1L, req);

        assertThat(id).isEqualTo(55L);
        ArgumentCaptor<KgEdge> captor = ArgumentCaptor.forClass(KgEdge.class);
        verify(edgeMapper).insert(captor.capture());
        KgEdge saved = captor.getValue();
        assertThat(saved.getCourseId()).isEqualTo(1L);
        assertThat(saved.getFromNodeId()).isEqualTo(1L);
        assertThat(saved.getToNodeId()).isEqualTo(2L);
        assertThat(saved.getRelationType()).isEqualTo(1);
        assertThat(saved.getRelationNote()).isEqualTo("教师手动画边");
        verify(courseAccessService, times(2)).assertManageCourse(1L);
    }

    @Test
    void createEdge_nonPrerequisiteIgnoresCycleCheck() {
        // 相关边反向不算环
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(node(1L, "N1")))
                .thenReturn(Collections.singletonList(node(2L, "N2")));
        when(edgeMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(edgeMapper.insert(any(KgEdge.class))).thenAnswer(inv -> {
            inv.getArgument(0, KgEdge.class).setId(66L);
            return 1;
        });

        Long id = service.createEdge(1L, edgeReq("N2", "N1", 3));
        assertThat(id).isEqualTo(66L);
        // 非前置：不应再查前置邻接
        verify(edgeMapper, never()).selectList(any());
        verify(edgeMapper).insert(any(KgEdge.class));
    }

    @Test
    void createNode_locksBeforeDuplicateReadAndInsert() {
        NodeUpsertRequest req = new NodeUpsertRequest();
        req.setNodeCode("LOCK-NODE");
        req.setName("lock node");
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        when(nodeMapper.insert(any(KgNode.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, KgNode.class).setId(101L);
            return 1;
        });

        service.createNode(1L, req);

        InOrder order = inOrder(courseAccessService, courseMutationLock, nodeMapper);
        order.verify(courseAccessService).assertManageCourse(1L);
        order.verify(courseMutationLock).lockActive(1L);
        order.verify(courseAccessService).assertManageCourse(1L);
        order.verify(nodeMapper).selectList(any(LambdaQueryWrapper.class));
        order.verify(nodeMapper).insert(any(KgNode.class));
    }

    @Test
    void updateNode_locksBeforeEntityReadAndWrite() {
        KgNode existing = node(1L, "N1");
        when(nodeMapper.selectById(1L)).thenReturn(existing);
        NodeUpsertRequest req = new NodeUpsertRequest();
        req.setNodeCode("N1");
        req.setName("updated");

        service.updateNode(1L, req);

        InOrder order = inOrder(courseAccessService, courseMutationLock, nodeMapper);
        order.verify(courseAccessService).assertManageNode(1L);
        order.verify(courseMutationLock).lockActive(1L);
        order.verify(courseAccessService).assertManageNode(1L);
        order.verify(nodeMapper).selectById(1L);
        order.verify(nodeMapper).updateById(any(KgNode.class));
    }

    @Test
    void createEdge_locksBeforeEndpointDuplicateAdjacencyReadsAndInsert() {
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(node(1L, "N1")))
                .thenReturn(Collections.singletonList(node(2L, "N2")))
                .thenReturn(Arrays.asList(node(1L, "N1"), node(2L, "N2")));
        when(edgeMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(edgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        service.createEdge(1L, edgeReq("N1", "N2", 1));

        InOrder order = inOrder(courseAccessService, courseMutationLock, nodeMapper, edgeMapper);
        order.verify(courseAccessService).assertManageCourse(1L);
        order.verify(courseMutationLock).lockActive(1L);
        order.verify(courseAccessService).assertManageCourse(1L);
        order.verify(nodeMapper, times(2)).selectList(any(LambdaQueryWrapper.class));
        order.verify(edgeMapper).selectCount(any(LambdaQueryWrapper.class));
        order.verify(nodeMapper).selectList(any(LambdaQueryWrapper.class));
        order.verify(edgeMapper).selectList(any(LambdaQueryWrapper.class));
        order.verify(edgeMapper).insert(any(KgEdge.class));
    }

    @Test
    void acceptPrerequisite_locksThenRereadsCandidateAndAdjacencyBeforeWrite() {
        KgEdge candidate = pendingPrerequisite(10L, 1L, 2L);
        when(edgeMapper.selectById(10L)).thenReturn(candidate);
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(node(1L, "N1"), node(2L, "N2")));
        when(edgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        service.acceptEdge(10L);

        InOrder order = inOrder(courseAccessService, courseMutationLock, edgeMapper, nodeMapper);
        order.verify(courseAccessService).assertManageEdge(10L);
        order.verify(courseMutationLock).lockActive(1L);
        order.verify(courseAccessService).assertManageEdge(10L);
        order.verify(edgeMapper).selectById(10L);
        order.verify(nodeMapper).selectList(any(LambdaQueryWrapper.class));
        order.verify(edgeMapper).selectList(any(LambdaQueryWrapper.class));
        order.verify(edgeMapper).updateById(any(KgEdge.class));
    }

    @Test
    void acceptPrerequisite_rejectsCycleUsingLockedAdjacency() {
        KgEdge candidate = pendingPrerequisite(10L, 2L, 1L);
        KgEdge existing = new KgEdge();
        existing.setId(11L);
        existing.setCourseId(1L);
        existing.setFromNodeId(1L);
        existing.setToNodeId(2L);
        existing.setRelationType(1);
        existing.setRelationNote("accepted");
        when(edgeMapper.selectById(10L)).thenReturn(candidate);
        when(nodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(node(1L, "N1"), node(2L, "N2")));
        when(edgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.acceptEdge(10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.CONFLICT.getCode());

        verify(edgeMapper, never()).updateById(any(KgEdge.class));
    }

    private static KgEdge pendingPrerequisite(long id, long fromNodeId, long toNodeId) {
        KgEdge edge = new KgEdge();
        edge.setId(id);
        edge.setCourseId(1L);
        edge.setFromNodeId(fromNodeId);
        edge.setToNodeId(toNodeId);
        edge.setRelationType(1);
        edge.setRelationNote(com.wenjin.support.ReviewMarker.PREFIX + "AI");
        return edge;
    }
}
