package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.dto.ReviewTodayVO;
import com.wenjin.dto.ReviewTodayVO.ReviewItemVO;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.ReviewService;
import com.wenjin.support.QuestionStatus;
import com.wenjin.support.RelationType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 今日复习队列（M3 §2.4）：筛选「掌握过、正在遗忘」的节点，按 urgency 降序截断。
 */
@Service
public class ReviewServiceImpl implements ReviewService {

    private static final int PREREQ = RelationType.PREREQUISITE.getCode();

    private final StudentMasteryMapper studentMasteryMapper;
    private final KgNodeMapper kgNodeMapper;
    private final KgEdgeMapper kgEdgeMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final QuestionMapper questionMapper;

    private final MasteryDecayCalculator decayCalculator;

    @Value("${wenjin.review.daily-size:5}")
    private int dailySize;

    public ReviewServiceImpl(StudentMasteryMapper studentMasteryMapper,
                             KgNodeMapper kgNodeMapper,
                             KgEdgeMapper kgEdgeMapper,
                             QuestionNodeMapper questionNodeMapper,
                             QuestionMapper questionMapper,
                             MasteryDecayCalculator decayCalculator) {
        this.studentMasteryMapper = studentMasteryMapper;
        this.kgNodeMapper = kgNodeMapper;
        this.kgEdgeMapper = kgEdgeMapper;
        this.questionNodeMapper = questionNodeMapper;
        this.questionMapper = questionMapper;
        this.decayCalculator = decayCalculator;
    }

    @Override
    public ReviewTodayVO getToday(Long studentId, Long courseId) {
        List<KgNode> nodes = kgNodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>().eq(KgNode::getCourseId, courseId));
        Map<Long, KgNode> nodeById = new HashMap<>();
        for (KgNode n : nodes) {
            nodeById.put(n.getId(), n);
        }

        List<StudentMastery> rows = studentMasteryMapper.selectList(
                new LambdaQueryWrapper<StudentMastery>()
                        .eq(StudentMastery::getStudentId, studentId)
                        .eq(StudentMastery::getCourseId, courseId));

        List<KgEdge> edges = kgEdgeMapper.selectList(
                new LambdaQueryWrapper<KgEdge>()
                        .eq(KgEdge::getCourseId, courseId)
                        .eq(KgEdge::getRelationType, PREREQ));
        Map<Long, Integer> outDegree = buildOutDegreeMap(edges);

        List<ReviewItemVO> decaying = new ArrayList<>();
        for (StudentMastery m : rows) {
            if (m.getMasteryScore() == null) {
                continue;
            }
            KgNode node = nodeById.get(m.getNodeId());
            if (node == null) {
                continue;
            }

            double raw = m.getMasteryScore().doubleValue();
            if (raw < decayCalculator.getMasteredThreshold()) {
                continue;
            }

            double effective = decayCalculator.effectiveScoreOf(m);
            if (effective >= decayCalculator.getMasteredThreshold()) {
                continue;
            }

            double days = decayCalculator.daysSinceEvidence(m.getUpdatedAt()); // for VO field
            int effLevel = decayCalculator.effectiveLevelOf(effective);
            double gap = raw - effective;
            boolean isKey = node.getIsKey() != null && node.getIsKey() == 1;
            int out = outDegree.getOrDefault(node.getId(), 0);
            double urgency = gap * (1.0 + (isKey ? 0.5 : 0.0)) * (1.0 + 0.1 * out);

            ReviewItemVO item = new ReviewItemVO();
            item.setNodeId(node.getId());
            item.setNodeCode(node.getNodeCode());
            item.setName(node.getName());
            item.setChapter(node.getChapter());
            item.setRawScore(raw);
            item.setEffectiveScore(effective);
            item.setEffectiveLevel(effLevel);
            item.setDaysSinceEvidence(days);
            item.setUrgency(urgency);
            decaying.add(item);
        }

        decaying.sort(Comparator
                .comparing(ReviewItemVO::getUrgency, Comparator.reverseOrder())
                .thenComparing(ReviewItemVO::getNodeId));

        int total = decaying.size();
        List<ReviewItemVO> top = decaying.stream().limit(dailySize).collect(Collectors.toList());

        List<Long> nodeIds = top.stream().map(ReviewItemVO::getNodeId).collect(Collectors.toList());
        Map<Long, Integer> availCountMap = buildAvailableCountMap(nodeIds);
        for (ReviewItemVO item : top) {
            item.setAvailableQuestions(availCountMap.getOrDefault(item.getNodeId(), 0));
        }

        ReviewTodayVO vo = new ReviewTodayVO();
        vo.setItems(top);
        vo.setTotalDecayingCount(total);
        return vo;
    }

    /** 出度：该节点作为 from_node 的前置边数量。 */
    private Map<Long, Integer> buildOutDegreeMap(List<KgEdge> edges) {
        Map<Long, Integer> out = new HashMap<>();
        for (KgEdge e : edges) {
            out.merge(e.getFromNodeId(), 1, Integer::sum);
        }
        return out;
    }

    /**
     * 批量查询各节点可用练习题数（口径与 PathServiceImpl 一致）。
     */
    private Map<Long, Integer> buildAvailableCountMap(List<Long> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return Map.of();
        }
        List<QuestionNode> links = questionNodeMapper.selectList(
                new LambdaQueryWrapper<QuestionNode>()
                        .in(QuestionNode::getNodeId, nodeIds));
        if (links.isEmpty()) {
            return Map.of();
        }
        List<Long> questionIds = links.stream()
                .map(QuestionNode::getQuestionId)
                .distinct()
                .collect(Collectors.toList());
        List<Question> approved = questionMapper.selectList(
                new LambdaQueryWrapper<Question>()
                        .in(Question::getId, questionIds)
                        .eq(Question::getStatus, QuestionStatus.APPROVED));
        Set<Long> approvedIds = approved.stream()
                .map(Question::getId)
                .collect(Collectors.toSet());

        Map<Long, Set<Long>> nodeToApprovedQs = new HashMap<>();
        for (QuestionNode link : links) {
            if (approvedIds.contains(link.getQuestionId())) {
                nodeToApprovedQs.computeIfAbsent(link.getNodeId(), k -> new HashSet<>())
                        .add(link.getQuestionId());
            }
        }
        Map<Long, Integer> countMap = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> e : nodeToApprovedQs.entrySet()) {
            countMap.put(e.getKey(), e.getValue().size());
        }
        return countMap;
    }
}