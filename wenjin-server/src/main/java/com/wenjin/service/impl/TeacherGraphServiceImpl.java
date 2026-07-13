package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
import com.wenjin.service.TeacherGraphService;
import com.wenjin.support.PrerequisiteCycleDetector;
import com.wenjin.support.RelationType;
import com.wenjin.support.ReviewMarker;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class TeacherGraphServiceImpl implements TeacherGraphService {

    private final KgNodeMapper nodeMapper;
    private final KgEdgeMapper edgeMapper;
    private final CourseAccessService courseAccessService;
    private final CourseMutationLock courseMutationLock;
    private final FeatureFlagsProperties featureFlags;

    public TeacherGraphServiceImpl(KgNodeMapper nodeMapper,
                                    KgEdgeMapper edgeMapper,
                                    CourseAccessService courseAccessService,
                                    CourseMutationLock courseMutationLock,
                                    FeatureFlagsProperties featureFlags) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.courseAccessService = courseAccessService;
        this.courseMutationLock = courseMutationLock;
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
        ensureGraphReconcileEnabled();
        if (edgeId == null || edgeId <= 0) {
            throw new IllegalArgumentException("edgeId 无效: " + edgeId);
        }
        Long courseId = courseAccessService.assertManageEdge(edgeId);
        courseMutationLock.lockActive(courseId);
        courseAccessService.assertManageEdge(edgeId);

        KgEdge edge = edgeMapper.selectById(edgeId);
        if (edge == null || edge.getArchivedAt() != null) {
            throw new IllegalArgumentException("边不存在: edgeId=" + edgeId);
        }

        if (Objects.equals(edge.getRelationType(), RelationType.PREREQUISITE.getCode())) {
            List<String> cycle = detectCycleIfAccept(courseId, edge);
            if (cycle != null) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "「前置」关系存在环路：" + String.join(" → ", cycle));
            }
        }

        edge.setRelationNote(ReviewMarker.strip(edge.getRelationNote()));
        edgeMapper.updateById(edge);
    }

    @Override
    @Transactional
    public void rejectEdge(Long edgeId) {
        ensureGraphReconcileEnabled();
        if (edgeId == null || edgeId <= 0) {
            throw new IllegalArgumentException("edgeId 无效: " + edgeId);
        }
        Long courseId = courseAccessService.assertManageEdge(edgeId);
        courseMutationLock.lockActive(courseId);
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
    public Long createEdge(Long courseId, EdgeCreateRequest req) {
        ensureGraphReconcileEnabled();
        if (courseId == null || courseId <= 0) {
            throw new IllegalArgumentException("courseId 无效: " + courseId);
        }
        // 归属校验优先：异课程管理权 → 404
        courseAccessService.assertManageCourse(courseId);
        courseMutationLock.lockActive(courseId);
        courseAccessService.assertManageCourse(courseId);
        if (req == null) {
            throw new IllegalArgumentException("请求参数不能为空");
        }

        String sourceCode = req.getSourceNodeCode() == null ? null : req.getSourceNodeCode().trim();
        String targetCode = req.getTargetNodeCode() == null ? null : req.getTargetNodeCode().trim();
        if (sourceCode == null || sourceCode.isEmpty()) {
            throw new IllegalArgumentException("起点节点编码不能为空");
        }
        if (targetCode == null || targetCode.isEmpty()) {
            throw new IllegalArgumentException("终点节点编码不能为空");
        }

        Integer relationType = req.getRelationType();
        if (relationType == null || relationType < 1 || relationType > 4) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "relationType 须在 1-4 之间");
        }

        // 自环
        if (sourceCode.equals(targetCode)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不允许自环边: " + sourceCode);
        }

        KgNode source = resolveActiveNode(courseId, sourceCode);
        KgNode target = resolveActiveNode(courseId, targetCode);
        if (source == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "起点节点不存在: nodeCode=" + sourceCode);
        }
        if (target == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "终点节点不存在: nodeCode=" + targetCode);
        }

        // 生效边重复（与 uk_edge 业务键一致）
        Long existing = edgeMapper.selectCount(
                new LambdaQueryWrapper<KgEdge>()
                        .eq(KgEdge::getCourseId, courseId)
                        .eq(KgEdge::getFromNodeId, source.getId())
                        .eq(KgEdge::getToNodeId, target.getId())
                        .eq(KgEdge::getRelationType, relationType)
                        .isNull(KgEdge::getArchivedAt));
        if (existing != null && existing > 0) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "边已存在: " + sourceCode + " → " + targetCode + "（type=" + relationType + "）");
        }

        // 前置环路：既有前置边 + 拟新增
        if (relationType == RelationType.PREREQUISITE.getCode()) {
            Map<String, List<String>> adj = buildPrerequisiteAdj(courseId);
            List<String> cycle = PrerequisiteCycleDetector.detectIfAdd(adj, sourceCode, targetCode);
            if (cycle != null) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "「前置」关系存在环路：" + String.join(" → ", cycle));
            }
        }

        KgEdge edge = new KgEdge();
        edge.setCourseId(courseId);
        edge.setFromNodeId(source.getId());
        edge.setToNodeId(target.getId());
        edge.setRelationType(relationType);
        edge.setRelationNote(req.getRelationNote());
        try {
            edgeMapper.insert(edge);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ResultCode.CONFLICT, "边已存在（唯一约束冲突）");
        } catch (DataIntegrityViolationException ex) {
            if (isDuplicateKey(ex)) {
                throw new BusinessException(ResultCode.CONFLICT, "边已存在（唯一约束冲突）");
            }
            throw ex;
        }
        return edge.getId();
    }

    /** 按 (courseId, nodeCode, archived_at IS NULL) 解析生效节点。 */
    private KgNode resolveActiveNode(Long courseId, String nodeCode) {
        List<KgNode> hits = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .eq(KgNode::getNodeCode, nodeCode)
                        .isNull(KgNode::getArchivedAt));
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** 构建本课程已接受的生效「前置」边邻接表（nodeCode → targets）。 */
    private Map<String, List<String>> buildPrerequisiteAdj(Long courseId) {
        List<KgNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .isNull(KgNode::getArchivedAt));
        Map<Long, String> idToCode = new HashMap<>();
        for (KgNode n : nodes) {
            idToCode.put(n.getId(), n.getNodeCode());
        }

        List<KgEdge> prereqs = edgeMapper.selectList(
                new LambdaQueryWrapper<KgEdge>()
                        .eq(KgEdge::getCourseId, courseId)
                        .eq(KgEdge::getRelationType, RelationType.PREREQUISITE.getCode())
                        .isNull(KgEdge::getArchivedAt));

        Map<String, List<String>> adj = new HashMap<>();
        for (KgEdge e : prereqs) {
            if (ReviewMarker.isPending(e.getRelationNote())) {
                continue;
            }
            String src = idToCode.get(e.getFromNodeId());
            String tgt = idToCode.get(e.getToNodeId());
            if (src == null || tgt == null) {
                continue;
            }
            adj.computeIfAbsent(src, k -> new ArrayList<>()).add(tgt);
        }
        return adj;
    }

    /** 候选待复核边不计入已接受图；持课程锁后用当前节点、边快照做最终环检测。 */
    private List<String> detectCycleIfAccept(Long courseId, KgEdge candidate) {
        List<KgNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .isNull(KgNode::getArchivedAt));
        Map<Long, String> idToCode = new HashMap<>();
        for (KgNode node : nodes) {
            idToCode.put(node.getId(), node.getNodeCode());
        }

        String sourceCode = idToCode.get(candidate.getFromNodeId());
        String targetCode = idToCode.get(candidate.getToNodeId());
        if (sourceCode == null || targetCode == null) {
            throw new IllegalArgumentException("边端点不存在: edgeId=" + candidate.getId());
        }

        List<KgEdge> prerequisites = edgeMapper.selectList(
                new LambdaQueryWrapper<KgEdge>()
                        .eq(KgEdge::getCourseId, courseId)
                        .eq(KgEdge::getRelationType, RelationType.PREREQUISITE.getCode())
                        .isNull(KgEdge::getArchivedAt));
        Map<String, List<String>> adjacency = new HashMap<>();
        for (KgEdge edge : prerequisites) {
            if (Objects.equals(edge.getId(), candidate.getId())
                    || ReviewMarker.isPending(edge.getRelationNote())) {
                continue;
            }
            String source = idToCode.get(edge.getFromNodeId());
            String target = idToCode.get(edge.getToNodeId());
            if (source != null && target != null) {
                adjacency.computeIfAbsent(source, ignored -> new ArrayList<>()).add(target);
            }
        }
        return PrerequisiteCycleDetector.detectIfAdd(adjacency, sourceCode, targetCode);
    }

    private static boolean isDuplicateKey(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            if (t instanceof DuplicateKeyException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getErrorCode() == 1062) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("duplicate") || lower.contains("uk_edge") || lower.contains("unique")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    @Override
    @Transactional
    public Long createNode(Long courseId, NodeUpsertRequest req) {
        ensureGraphReconcileEnabled();
        if (courseId == null || courseId <= 0) {
            throw new IllegalArgumentException("courseId 无效: " + courseId);
        }
        courseAccessService.assertManageCourse(courseId);
        courseMutationLock.lockActive(courseId);
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
        ensureGraphReconcileEnabled();
        if (nodeId == null || nodeId <= 0) {
            throw new IllegalArgumentException("nodeId 无效: " + nodeId);
        }
        Long courseId = courseAccessService.assertManageNode(nodeId);
        courseMutationLock.lockActive(courseId);
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
        ensureGraphReconcileEnabled();
        if (nodeId == null || nodeId <= 0) {
            throw new IllegalArgumentException("nodeId 无效: " + nodeId);
        }
        Long courseId = courseAccessService.assertManageNode(nodeId);
        courseMutationLock.lockActive(courseId);
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
