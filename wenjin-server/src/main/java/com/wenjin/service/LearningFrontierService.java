package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.support.PrereqDirection;
import com.wenjin.support.ReviewMarker;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 学习前沿：直接前置全部已掌握（有效掌握度 ≥ 阈值）而自身尚未掌握的节点。
 * 供伴侣工具 get_learning_frontier 与主动开场白共用（零 LLM、只读）。
 */
@Service
public class LearningFrontierService {

    /** 前沿节点；effective 为衰减后的有效掌握度（无记录为 0）。 */
    public record FrontierNode(String code, String name, double effective) {}

    private final KgNodeMapper nodeMapper;
    private final KgEdgeMapper edgeMapper;
    private final StudentMasteryMapper masteryMapper;
    private final MasteryDecayCalculator decayCalculator;

    public LearningFrontierService(KgNodeMapper nodeMapper,
                                   KgEdgeMapper edgeMapper,
                                   StudentMasteryMapper masteryMapper,
                                   MasteryDecayCalculator decayCalculator) {
        this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
        this.edgeMapper = Objects.requireNonNull(edgeMapper, "edgeMapper");
        this.masteryMapper = Objects.requireNonNull(masteryMapper, "masteryMapper");
        this.decayCalculator = Objects.requireNonNull(decayCalculator, "decayCalculator");
    }

    /**
     * @return 按有效掌握度升序、再按 code 排序的前沿节点，至多 limit 个
     */
    public List<FrontierNode> frontier(long studentId, long courseId, int limit) {
        List<KgNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId)
                .isNull(KgNode::getArchivedAt));
        Map<Long, KgNode> nodeById = nodes.stream()
                .collect(Collectors.toMap(KgNode::getId, Function.identity(), (a, b) -> a));

        List<StudentMastery> masteries = masteryMapper.selectList(new LambdaQueryWrapper<StudentMastery>()
                .eq(StudentMastery::getStudentId, studentId)
                .eq(StudentMastery::getCourseId, courseId));
        Map<Long, Double> effectiveByNodeId = new HashMap<>();
        for (StudentMastery m : masteries) {
            if (m.getMasteryScore() == null) {
                continue;
            }
            effectiveByNodeId.put(m.getNodeId(), decayCalculator.effectiveScoreOf(m));
        }

        List<KgEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<KgEdge>()
                .eq(KgEdge::getCourseId, courseId)
                .eq(KgEdge::getRelationType, PrereqDirection.RELATION_TYPE_PREREQUISITE)
                .isNull(KgEdge::getArchivedAt));
        Map<Long, List<Long>> prereqOf = new HashMap<>();
        for (KgEdge edge : edges) {
            if (ReviewMarker.isPending(edge.getRelationNote())) {
                continue;
            }
            Long prereqId = PrereqDirection.prerequisiteNodeId(edge);
            Long dependentId = PrereqDirection.dependentNodeId(edge);
            if (prereqId == null || dependentId == null) {
                continue;
            }
            if (!nodeById.containsKey(prereqId) || !nodeById.containsKey(dependentId)) {
                continue;
            }
            prereqOf.computeIfAbsent(dependentId, k -> new ArrayList<>()).add(prereqId);
        }

        double threshold = decayCalculator.getMasteredThreshold();
        List<FrontierNode> frontier = new ArrayList<>();
        for (KgNode node : nodes) {
            double selfEffective = effectiveByNodeId.getOrDefault(node.getId(), 0.0);
            if (selfEffective >= threshold) {
                continue;
            }
            boolean allPrereqsMastered = true;
            for (Long prereqId : prereqOf.getOrDefault(node.getId(), List.of())) {
                if (effectiveByNodeId.getOrDefault(prereqId, 0.0) < threshold) {
                    allPrereqsMastered = false;
                    break;
                }
            }
            if (allPrereqsMastered) {
                frontier.add(new FrontierNode(node.getNodeCode(), node.getName(), selfEffective));
            }
        }

        frontier.sort(Comparator.comparingDouble(FrontierNode::effective)
                .thenComparing(n -> String.valueOf(n.code())));
        int cap = Math.max(0, limit);
        return frontier.size() > cap ? new ArrayList<>(frontier.subList(0, cap)) : frontier;
    }
}
