package com.wenjin.service.impl;

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
import com.wenjin.service.TeacherGraphService;
import com.wenjin.support.RelationType;
import com.wenjin.support.ReviewMarker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TeacherGraphServiceImpl implements TeacherGraphService {

    private final KgNodeMapper nodeMapper;
    private final KgEdgeMapper edgeMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final CourseMapper courseMapper;

    public TeacherGraphServiceImpl(KgNodeMapper nodeMapper,
                                    KgEdgeMapper edgeMapper,
                                    QuestionNodeMapper questionNodeMapper,
                                    CourseMapper courseMapper) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.questionNodeMapper = questionNodeMapper;
        this.courseMapper = courseMapper;
    }

    @Override
    public TeacherGraphVO getGraph(Long courseId) {
        if (courseId == null || courseId <= 0) {
            throw new IllegalArgumentException("courseId 无效: " + courseId);
        }

        List<KgNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>().eq(KgNode::getCourseId, courseId)
        );
        List<KgEdge> edges = edgeMapper.selectList(
                new LambdaQueryWrapper<KgEdge>().eq(KgEdge::getCourseId, courseId)
        );

        // Build id->code mapping for edges
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

        List<KgNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>().eq(KgNode::getCourseId, courseId)
        );

        Map<Long, KgNode> idToNode = new HashMap<>();
        for (KgNode n : nodes) {
            idToNode.put(n.getId(), n);
        }

        List<KgEdge> edges = edgeMapper.selectList(
                new LambdaQueryWrapper<KgEdge>().eq(KgEdge::getCourseId, courseId)
        );

        return edges.stream()
                .filter(e -> ReviewMarker.isPending(e.getRelationNote()))
                .sorted(Comparator.comparing(KgEdge::getConfidence,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(e -> {
                    KgNode fromNode = idToNode.get(e.getFromNodeId());
                    KgNode toNode = idToNode.get(e.getToNodeId());

                    // 跳过脏数据边（节点已删除）
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

        KgEdge edge = edgeMapper.selectById(edgeId);
        if (edge == null) {
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

        KgEdge edge = edgeMapper.selectById(edgeId);
        if (edge == null) {
            throw new IllegalArgumentException("边不存在: edgeId=" + edgeId);
        }

        edgeMapper.deleteById(edgeId);
    }

    @Override
    @Transactional
    public Long createNode(Long courseId, NodeUpsertRequest req) {
        if (courseId == null || courseId <= 0) {
            throw new IllegalArgumentException("courseId 无效: " + courseId);
        }
        if (req == null) {
            throw new IllegalArgumentException("请求参数不能为空");
        }
        if (req.getNodeCode() == null || req.getNodeCode().trim().isEmpty()) {
            throw new IllegalArgumentException("节点编码不能为空");
        }

        // 检查课程存在性
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new IllegalArgumentException("课程不存在: courseId=" + courseId);
        }

        // Check duplicate
        List<KgNode> existing = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .eq(KgNode::getNodeCode, req.getNodeCode().trim())
        );
        if (!existing.isEmpty()) {
            throw new IllegalArgumentException("节点编码已存在: nodeCode=" + req.getNodeCode());
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
        if (req == null) {
            throw new IllegalArgumentException("请求参数不能为空");
        }

        KgNode node = nodeMapper.selectById(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("节点不存在: nodeId=" + nodeId);
        }

        // 拒绝修改 nodeCode
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

        KgNode node = nodeMapper.selectById(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("节点不存在: nodeId=" + nodeId);
        }

        // Delete edges where this node is from or to (by nodeId, not code)
        edgeMapper.delete(new LambdaQueryWrapper<KgEdge>().eq(KgEdge::getFromNodeId, nodeId));
        edgeMapper.delete(new LambdaQueryWrapper<KgEdge>().eq(KgEdge::getToNodeId, nodeId));

        // Delete question_node associations (by nodeId)
        questionNodeMapper.delete(
                new LambdaQueryWrapper<com.wenjin.entity.QuestionNode>()
                        .eq(com.wenjin.entity.QuestionNode::getNodeId, nodeId)
        );

        // Delete the node itself
        nodeMapper.deleteById(nodeId);
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
        // 保留原始 relationNote（含"待复核"前缀），pending 字段已标记状态，教师端需完整信息
        vo.setNote(edge.getRelationNote());
        vo.setConfidence(edge.getConfidence());
        vo.setPending(ReviewMarker.isPending(edge.getRelationNote()));
        return vo;
    }

    private void applyRequest(KgNode node, NodeUpsertRequest req) {
        // nodeCode 仅在创建时设置，更新时不可改
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
