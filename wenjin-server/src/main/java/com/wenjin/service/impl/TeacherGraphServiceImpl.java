package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.NodeUpsertRequest;
import com.wenjin.dto.PendingEdgeVO;
import com.wenjin.dto.TeacherGraphVO;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.service.TeacherGraphService;
import com.wenjin.support.RelationType;
import com.wenjin.support.ReviewMarker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TeacherGraphServiceImpl implements TeacherGraphService {

    private final KgNodeMapper nodeMapper;
    private final KgEdgeMapper edgeMapper;
    private final CourseAccessService courseAccessService;
    private final FeatureFlagsProperties featureFlags;

    public TeacherGraphServiceImpl(KgNodeMapper nodeMapper,
                                    KgEdgeMapper edgeMapper,
                                    CourseAccessService courseAccessService,
                                    FeatureFlagsProperties featureFlags) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.courseAccessService = courseAccessService;
        this.featureFlags = featureFlags;
    }

    @Override
    public TeacherGraphVO getGraph(Long courseId) {
        if (courseId == null || courseId <= 0) {
            throw new IllegalArgumentException("courseId 无效: " + courseId);
        }
        courseAccessService.assertManageCourse(courseId);

        List<KgNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .isNull(KgNode::getArchivedAt)
        );
        List<KgEdge> edges = edgeMapper.selectList(
                new LambdaQueryWrapper<KgEdge>()
                        .eq(KgEdge::getCourseId, courseId)
                        .isNull(KgEdge::getArchivedAt)
        );

        Map<Long, String> idToCode = new HashMap<>();
        for (KgNode n : nodes) {
            idToCode.put(n.getId(), n.getNodeCode());
        }

        TeacherGraphVO vo = new TeacherGraphVO();
        vo.setNodes(nodes.stream().map(this::toNodeVO).collect(Collectors.toList()));
        vo.setEdges(edges.stream().map(e -> toEdgeVO(e, idToCode)).collect(Collectors.toList()));
        return vo;
    }

    @Override
    public List<PendingEdgeVO> pendingEdges(Long courseId) {
        if (courseId == null || courseId <= 0) {
            throw new IllegalArgumentException("courseId 无效: " + courseId);
        }
        courseAccessService.assertManageCourse(courseId);

        List<KgNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .isNull(KgNode::getArchivedAt)
        );

        Map<Long, KgNode> idToNode = new HashMap<>();
        for (KgNode n : nodes) {
            idToNode.put(n.getId(), n);
        }

        List<KgEdge> edges = edgeMapper.selectList(
                new LambdaQueryWrapper<KgEdge>()
                        .eq(KgEdge::getCourseId, courseId)
                        .isNull(KgEdge::getArchivedAt)
        );

        return edges.stream()
                .filter(e -> ReviewMarker.isPending(e.getRelationNote()))
                .sorted(Comparator.comparing(KgEdge::getConfidence,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(e -> {
                    KgNode fromNode = idToNode.get(e.getFromNodeId());
                    KgNode toNode = idToNode.get(e.getToNodeId());

                    // 跳过脏数据边（节点已删除/归档）
                    if (fromNode == null || toNode == null) {
                        return null;
                    }

                    PendingEdgeVO vo = new PendingEdgeVO();
                    vo.setId(e.getId());
                    vo.setFromCode(fromNode.getNodeCode());
                    vo.setFromName(fromNode.getName());
                    vo.setToCode(toNode.getNodeCode());
                    vo.setToName(toNode.getName());
                    vo.setRelationType(RelationType.labelOf(e.getRelationType()));
                    vo.setConfidence(e.getConfidence());
                    vo.setReason(ReviewMarker.strip(e.getRelationNote()));
                    vo.setLow(e.getConfidence() != null && e.getConfidence() < 70);
                    return vo;
                })
                .filter(vo -> vo != null)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void acceptEdge(Long edgeId) {
        if (edgeId == null || edgeId <= 0) {
            throw new IllegalArgumentException("edgeId 无效: " + edgeId);
        }
        courseAccessService.assertManageEdge(edgeId);

        KgEdge edge = edgeMapper.selectById(edgeId);
        if (edge == null || edge.getArchivedAt() != null) {
            throw new IllegalArgumentException("边不存在: edgeId=" + edgeId);
        }

        edge.setRelationNote(ReviewMarker.strip(edge.getRelationNote()));
        edgeMapper.updateById(edge);
    }

    @Override
    @Transactional
    public void rejectEdge(Long edgeId) {
        if (edgeId == null || edgeId <= 0) {
            throw new IllegalArgumentException("edgeId 无效: " + edgeId);
        }
        ensureGraphReconcileEnabled();
        courseAccessService.assertManageEdge(edgeId);

        KgEdge edge = edgeMapper.selectById(edgeId);
        if (edge == null || edge.getArchivedAt() != null) {
            throw new IllegalArgumentException("边不存在: edgeId=" + edgeId);
        }

        // 软归档，不硬删
        edge.setArchivedAt(LocalDateTime.now());
        edgeMapper.updateById(edge);
    }

    @Override
    @Transactional
    public Long createNode(Long courseId, NodeUpsertRequest req) {
        if (courseId == null || courseId <= 0) {
            throw new IllegalArgumentException("courseId 无效: " + courseId);
        }
        ensureGraphReconcileEnabled();
        courseAccessService.assertManageCourse(courseId);
        if (req == null) {
            throw new IllegalArgumentException("请求参数不能为空");
        }
        if (req.getNodeCode() == null || req.getNodeCode().trim().isEmpty()) {
            throw new IllegalArgumentException("节点编码不能为空");
        }

        // 含归档节点：uk_course_code 仍占用；若仅归档则恢复并更新字段（与 import 一致）
        List<KgNode> existing = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .eq(KgNode::getNodeCode, req.getNodeCode().trim())
        );
        if (!existing.isEmpty()) {
            KgNode hit = existing.get(0);
            if (hit.getArchivedAt() == null) {
                throw new IllegalArgumentException("节点编码已存在: nodeCode=" + req.getNodeCode());
            }
            applyRequest(hit, req);
            // updateById 忽略 null：用 UpdateWrapper 清 archived_at
            nodeMapper.update(null, new UpdateWrapper<KgNode>()
                    .eq("id", hit.getId())
                    .set("name", hit.getName())
                    .set("chapter", hit.getChapter())
                    .set("difficulty", hit.getDifficulty())
                    .set("is_key", hit.getIsKey())
                    .set("description", hit.getDescription())
                    .set("node_note", hit.getNodeNote())
                    .set("archived_at", null));
            return hit.getId();
        }

        KgNode node = new KgNode();
        node.setCourseId(courseId);
        applyRequest(node, req);
        nodeMapper.insert(node);
        return node.getId();
    }

    @Override
    @Transactional
    public void updateNode(Long nodeId, NodeUpsertRequest req) {
        if (nodeId == null || nodeId <= 0) {
            throw new IllegalArgumentException("nodeId 无效: " + nodeId);
        }
        ensureGraphReconcileEnabled();
        courseAccessService.assertManageNode(nodeId);
        if (req == null) {
            throw new IllegalArgumentException("请求参数不能为空");
        }

        KgNode node = nodeMapper.selectById(nodeId);
        if (node == null || node.getArchivedAt() != null) {
            throw new IllegalArgumentException("节点不存在: nodeId=" + nodeId);
        }

        if (req.getNodeCode() != null && !req.getNodeCode().equals(node.getNodeCode())) {
            throw new IllegalArgumentException("节点编码不可修改: nodeCode=" + node.getNodeCode());
        }

        applyRequest(node, req);
        nodeMapper.updateById(node);
    }

    @Override
    @Transactional
    public void deleteNode(Long nodeId) {
        if (nodeId == null || nodeId <= 0) {
            throw new IllegalArgumentException("nodeId 无效: " + nodeId);
        }
        ensureGraphReconcileEnabled();
        courseAccessService.assertManageNode(nodeId);

        KgNode node = nodeMapper.selectById(nodeId);
        if (node == null || node.getArchivedAt() != null) {
            throw new IllegalArgumentException("节点不存在: nodeId=" + nodeId);
        }

        LocalDateTime now = LocalDateTime.now();
        // 归档关联边；不硬删 question_node / mastery
        edgeMapper.update(null, new UpdateWrapper<KgEdge>()
                .and(w -> w.eq("from_node_id", nodeId).or().eq("to_node_id", nodeId))
                .isNull("archived_at")
                .set("archived_at", now));

        node.setArchivedAt(now);
        nodeMapper.updateById(node);
    }

    private void ensureGraphReconcileEnabled() {
        if (featureFlags == null || !featureFlags.isGraphReconcileEnabled()) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "图谱对账/导入功能暂未开放");
        }
    }

    private TeacherGraphVO.NodeVO toNodeVO(KgNode node) {
        TeacherGraphVO.NodeVO vo = new TeacherGraphVO.NodeVO();
        vo.setId(node.getId());
        vo.setNodeCode(node.getNodeCode());
        vo.setName(node.getName());
        vo.setChapter(node.getChapter());
        vo.setDifficulty(node.getDifficulty());
        vo.setIsKey(node.getIsKey() != null && node.getIsKey() == 1);
        vo.setDescription(node.getDescription());
        vo.setNote(node.getNodeNote());
        return vo;
    }

    private TeacherGraphVO.EdgeVO toEdgeVO(KgEdge edge, Map<Long, String> idToCode) {
        TeacherGraphVO.EdgeVO vo = new TeacherGraphVO.EdgeVO();
        vo.setId(edge.getId());
        vo.setSource(idToCode.getOrDefault(edge.getFromNodeId(), ""));
        vo.setTarget(idToCode.getOrDefault(edge.getToNodeId(), ""));
        vo.setType(RelationType.labelOf(edge.getRelationType()));
        vo.setNote(edge.getRelationNote());
        vo.setConfidence(edge.getConfidence());
        vo.setPending(ReviewMarker.isPending(edge.getRelationNote()));
        return vo;
    }

    private void applyRequest(KgNode node, NodeUpsertRequest req) {
        if (node.getId() == null && req.getNodeCode() != null) {
            node.setNodeCode(req.getNodeCode());
        }
        if (req.getName() != null) node.setName(req.getName());
        if (req.getChapter() != null) node.setChapter(req.getChapter());
        if (req.getDifficulty() != null) node.setDifficulty(req.getDifficulty());
        if (req.getIsKey() != null) node.setIsKey(req.getIsKey() ? 1 : 0);
        if (req.getDescription() != null) node.setDescription(req.getDescription());
        if (req.getNote() != null) node.setNodeNote(req.getNote());
    }
}
