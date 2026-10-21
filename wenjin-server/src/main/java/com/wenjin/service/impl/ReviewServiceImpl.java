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
import com.wenjin.service.companion.ReviewScheduleStore;
import com.wenjin.support.QuestionStatus;
import com.wenjin.support.RelationType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 今日复习队列（M3 §2.4）：筛选「掌握过、正在遗忘」的节点，按 urgency 降序截断；
 * 并合并伴侣 {@code schedule_review} 的当日置顶（持久化在 review_pin）。
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
    private final ReviewScheduleStore scheduleStore;

    @Value("${wenjin.review.daily-size:5}")
    private int dailySize;

    public ReviewServiceImpl(StudentMasteryMapper studentMasteryMapper,
                             KgNodeMapper kgNodeMapper,
                             KgEdgeMapper kgEdgeMapper,
                             QuestionNodeMapper questionNodeMapper,
                             QuestionMapper questionMapper,
                             MasteryDecayCalculator decayCalculator,
                             ReviewScheduleStore scheduleStore) {
        this.studentMasteryMapper = studentMasteryMapper;
        this.kgNodeMapper = kgNodeMapper;
        this.kgEdgeMapper = kgEdgeMapper;
        this.questionNodeMapper = questionNodeMapper;
        this.questionMapper = questionMapper;
        this.decayCalculator = decayCalculator;
        this.scheduleStore = scheduleStore;
    }

    @Override
    public ReviewTodayVO getToday(Long studentId, Long courseId) {
        List<KgNode> nodes = kgNodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .isNull(KgNode::getArchivedAt));
        Map<Long, KgNode> nodeById = new HashMap<>();
        Map<String, KgNode> nodeByCode = new HashMap<>();
        for (KgNode n : nodes) {
            nodeById.put(n.getId(), n);
            if (n.getNodeCode() != null) {
                nodeByCode.put(n.getNodeCode(), n);
            }
        }

        List<StudentMastery> rows = studentMasteryMapper.selectList(
                new LambdaQueryWrapper<StudentMastery>()
                        .eq(StudentMastery::getStudentId, studentId)
                        .eq(StudentMastery::getCourseId, courseId));
        Map<Long, StudentMastery> masteryByNodeId = new HashMap<>();
        for (StudentMastery m : rows) {
            if (m.getNodeId() != null) {
                masteryByNodeId.put(m.getNodeId(), m);
            }
        }

        List<KgEdge> edges = kgEdgeMapper.selectList(
                new LambdaQueryWrapper<KgEdge>()
                        .eq(KgEdge::getCourseId, courseId)
                        .eq(KgEdge::getRelationType, PREREQ)
                        .isNull(KgEdge::getArchivedAt));
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

            double days = decayCalculator.daysSinceEvidence(m.getUpdatedAt());
            double effective = decayCalculator.effectiveScoreOf(m, days);
            if (effective >= decayCalculator.getMasteredThreshold()) {
                continue;
            }
            int effLevel = decayCalculator.effectiveLevelOf(effective);
            double gap = raw - effective;
            boolean isKey = node.getIsKey() != null && node.getIsKey() == 1;
            int out = outDegree.getOrDefault(node.getId(), 0);
            double urgency = gap * (1.0 + (isKey ? 0.5 : 0.0)) * (1.0 + 0.1 * out);

            ReviewItemVO item = baseItem(node, raw, effective, effLevel, days, urgency);
            item.setForced(false);
            decaying.add(item);
        }

        decaying.sort(Comparator
                .comparing(ReviewItemVO::getUrgency, Comparator.reverseOrder())
                .thenComparing(ReviewItemVO::getNodeId));

        int totalDecaying = decaying.size();
        Map<String, ReviewItemVO> decayingByCode = new LinkedHashMap<>();
        for (ReviewItemVO item : decaying) {
            if (item.getNodeCode() != null) {
                decayingByCode.putIfAbsent(item.getNodeCode(), item);
            }
        }

        // Forced pins first (durable companion schedule_review)
        Map<String, ReviewScheduleStore.Mode> forced =
                scheduleStore != null ? scheduleStore.forcedToday(studentId, courseId) : Map.of();
        List<ReviewItemVO> merged = new ArrayList<>();
        Set<String> usedCodes = new LinkedHashSet<>();
        Set<Long> usedIds = new HashSet<>();

        for (Map.Entry<String, ReviewScheduleStore.Mode> entry : forced.entrySet()) {
            String code = entry.getKey();
            ReviewItemVO fromDecay = decayingByCode.get(code);
            ReviewItemVO item;
            if (fromDecay != null) {
                item = fromDecay;
            } else {
                KgNode node = nodeByCode.get(code);
                if (node == null) {
                    continue;
                }
                item = buildPinnedItem(node, masteryByNodeId.get(node.getId()));
            }
            item.setForced(true);
            item.setPinMode(entry.getValue().name());
            merged.add(item);
            usedCodes.add(code);
            if (item.getNodeId() != null) {
                usedIds.add(item.getNodeId());
            }
        }

        // Fill remaining slots with decaying urgency list
        int remaining = Math.max(0, dailySize);
        for (ReviewItemVO item : decaying) {
            if (remaining <= 0) {
                break;
            }
            if (item.getNodeCode() != null && usedCodes.contains(item.getNodeCode())) {
                continue;
            }
            if (item.getNodeId() != null && usedIds.contains(item.getNodeId())) {
                continue;
            }
            if (item.getForced() == null) {
                item.setForced(false);
            }
            merged.add(item);
            if (item.getNodeCode() != null) {
                usedCodes.add(item.getNodeCode());
            }
            if (item.getNodeId() != null) {
                usedIds.add(item.getNodeId());
            }
            remaining--;
        }

        List<Long> nodeIds = merged.stream()
                .map(ReviewItemVO::getNodeId)
                .filter(id -> id != null)
                .collect(Collectors.toList());
        Map<Long, Integer> availCountMap = buildAvailableCountMap(nodeIds);
        for (ReviewItemVO item : merged) {
            if (item.getNodeId() != null) {
                item.setAvailableQuestions(availCountMap.getOrDefault(item.getNodeId(), 0));
            } else if (item.getAvailableQuestions() == null) {
                item.setAvailableQuestions(0);
            }
        }

        ReviewTodayVO vo = new ReviewTodayVO();
        vo.setItems(merged);
        // 展示用：衰减总数 + 不在衰减列表里的置顶数
        int extraPins = 0;
        for (String code : forced.keySet()) {
            if (!decayingByCode.containsKey(code) && nodeByCode.containsKey(code)) {
                extraPins++;
            }
        }
        vo.setTotalDecayingCount(totalDecaying + extraPins);
        return vo;
    }

    private ReviewItemVO baseItem(KgNode node, double raw, double effective, int effLevel,
                                  double days, Double urgency) {
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
        return item;
    }

    private ReviewItemVO buildPinnedItem(KgNode node, StudentMastery mastery) {
        if (mastery != null && mastery.getMasteryScore() != null) {
            double raw = mastery.getMasteryScore().doubleValue();
            double days = decayCalculator.daysSinceEvidence(mastery.getUpdatedAt());
            double effective = decayCalculator.effectiveScoreOf(mastery, days);
            int effLevel = decayCalculator.effectiveLevelOf(effective);
            // 手动置顶优先级高于衰减 urgency
            return baseItem(node, raw, effective, effLevel, days, Double.MAX_VALUE);
        }
        return baseItem(node, 0.0, 0.0, 0, 0.0, Double.MAX_VALUE);
    }

    private Map<Long, Integer> buildOutDegreeMap(List<KgEdge> edges) {
        Map<Long, Integer> out = new HashMap<>();
        for (KgEdge e : edges) {
            out.merge(e.getFromNodeId(), 1, Integer::sum);
        }
        return out;
    }

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
