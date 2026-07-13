package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.ai.QuestionAiClient;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AccessGuard;
import com.wenjin.dto.DiagnosticResultVO;
import com.wenjin.dto.LearningPathVO;
import com.wenjin.dto.LearningPathVO.NodeRef;
import com.wenjin.dto.LearningPathVO.Progress;
import com.wenjin.dto.LearningPathVO.StepVO;
import com.wenjin.dto.PathGenerateRequest;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.LearningPath;
import com.wenjin.entity.LearningPathItem;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.StudentCourse;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.LearningPathItemMapper;
import com.wenjin.mapper.LearningPathMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.StudentCourseMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.DiagnosticResultService;
import com.wenjin.service.LearningPathWriter;
import com.wenjin.service.LearningPathWriter.PreparedPathStep;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.PathService;
import com.wenjin.support.QuestionStatus;
import com.wenjin.support.RelationType;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 学习路径服务实现（PRD 8.3）。
 * <p>聚焦卡点：从 target 沿前置边逆向 BFS 收集未掌握前置，Kahn 拓扑排序后落库。
 * <p>并发：读快照与 AI 理由在事务外；短写由 {@link LearningPathWriter} 加入调用方事务或独立短事务完成。
 */
@Service
public class PathServiceImpl implements PathService {

    private static final int PREREQ = RelationType.PREREQUISITE.getCode();
    private static final int ITEM_DONE = 1;
    private static final String CONCLUSION_TEXT = "从根因补起，沿前置链逐步推进——根基稳了，卡点自然松动。";
    private final StudentMasteryMapper studentMasteryMapper;
    private final KgNodeMapper kgNodeMapper;
    private final KgEdgeMapper kgEdgeMapper;
    private final LearningPathMapper learningPathMapper;
    private final LearningPathItemMapper learningPathItemMapper;
    private final StudentCourseMapper studentCourseMapper;
    private final DiagnosticResultService diagnosticResultService;
    private final QuestionAiClient questionAiClient;
    private final QuestionNodeMapper questionNodeMapper;
    private final QuestionMapper questionMapper;
    private final MasteryDecayCalculator decayCalculator;
    private final LearningPathWriter learningPathWriter;

    public PathServiceImpl(StudentMasteryMapper studentMasteryMapper, KgNodeMapper kgNodeMapper,
                           KgEdgeMapper kgEdgeMapper, LearningPathMapper learningPathMapper,
                           LearningPathItemMapper learningPathItemMapper,
                           StudentCourseMapper studentCourseMapper,
                           DiagnosticResultService diagnosticResultService,
                           QuestionAiClient questionAiClient,
                           QuestionNodeMapper questionNodeMapper,
                           QuestionMapper questionMapper,
                           MasteryDecayCalculator decayCalculator,
                           LearningPathWriter learningPathWriter) {
        this.studentMasteryMapper = studentMasteryMapper;
        this.kgNodeMapper = kgNodeMapper;
        this.kgEdgeMapper = kgEdgeMapper;
        this.learningPathMapper = learningPathMapper;
        this.learningPathItemMapper = learningPathItemMapper;
        this.studentCourseMapper = studentCourseMapper;
        this.diagnosticResultService = diagnosticResultService;
        this.questionAiClient = questionAiClient;
        this.questionNodeMapper = questionNodeMapper;
        this.questionMapper = questionMapper;
        this.decayCalculator = decayCalculator;
        this.learningPathWriter = learningPathWriter;
    }

    @Override
    public LearningPathVO generate(PathGenerateRequest req) {
        Long studentId = req.getStudentId();
        Long courseId = req.getCourseId();

        // ── 事务外：读图谱 / 掌握度快照，计算拓扑 ──────────────────────────
        List<KgNode> nodes = kgNodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .isNull(KgNode::getArchivedAt));
        Map<Long, KgNode> nodeById = new HashMap<>();
        Map<String, Long> idByCode = new HashMap<>();
        for (KgNode n : nodes) {
            nodeById.put(n.getId(), n);
            idByCode.put(n.getNodeCode(), n.getId());
        }

        Long targetId = req.getTargetNodeId();
        if (targetId == null) {
            DiagnosticResultVO diag = diagnosticResultService.getResult(studentId, courseId);
            if (!diag.isHasWeakness() || diag.getStuckNode() == null) {
                return emptyPath();
            }
            targetId = idByCode.get(diag.getStuckNode().getNodeCode());
        }
        if (targetId == null || !nodeById.containsKey(targetId)) {
            return emptyPath();
        }

        List<StudentMastery> rows = studentMasteryMapper.selectList(
                new LambdaQueryWrapper<StudentMastery>()
                        .eq(StudentMastery::getStudentId, studentId)
                        .eq(StudentMastery::getCourseId, courseId));
        Map<Long, StudentMastery> mById = new HashMap<>();
        for (StudentMastery m : rows) {
            mById.put(m.getNodeId(), m);
        }

        List<KgEdge> edges = kgEdgeMapper.selectList(
                new LambdaQueryWrapper<KgEdge>()
                        .eq(KgEdge::getCourseId, courseId)
                        .eq(KgEdge::getRelationType, PREREQ)
                        .isNull(KgEdge::getArchivedAt));
        Map<Long, List<Long>> prereqMap = new HashMap<>();
        for (KgEdge e : edges) {
            prereqMap.computeIfAbsent(e.getToNodeId(), k -> new ArrayList<>()).add(e.getFromNodeId());
        }

        Set<Long> setNodes = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        setNodes.add(targetId);
        queue.add(targetId);
        while (!queue.isEmpty()) {
            Long cur = queue.poll();
            for (Long p : prereqMap.getOrDefault(cur, List.of())) {
                if (isMastered(mById, p)) {
                    continue;
                }
                if (setNodes.add(p)) {
                    queue.add(p);
                }
            }
        }

        List<Long> ordered = topoSort(setNodes, prereqMap, mById);
        Set<Long> rootSet = rootNodeIds(setNodes, prereqMap);

        String targetName = nodeById.get(targetId).getName();
        Map<Long, Integer> availCountMap = buildAvailableCountMap(ordered);

        // ── 事务外：AI / 模板理由（绝不持锁调 AI） ─────────────────────────
        List<PreparedPathStep> prepared = new ArrayList<>();
        int order = 1;
        for (Long id : ordered) {
            String role = roleOf(id, targetId, rootSet);
            String reason = resolveReason(nodeById.get(id).getName(), role, targetName, req.isUseAi());
            prepared.add(new PreparedPathStep(id, order, role, reason));
            order++;
        }

        // ── 路径写入（LearningPathWriter 负责 ambient/standalone 事务边界） ──
        final Long finalTargetId = targetId;
        LearningPath path = learningPathWriter.persistPath(
                studentId, courseId, finalTargetId, prepared);

        List<StepVO> steps = new ArrayList<>();
        for (PreparedPathStep ps : prepared) {
            LearningPathItem item = new LearningPathItem();
            item.setId(ps.itemId());
            item.setPathId(path.getId());
            item.setNodeId(ps.nodeId());
            item.setStepOrder(ps.stepOrder());
            item.setStatus(0);
            item.setReason(ps.reason());
            steps.add(toStep(item, nodeById.get(ps.nodeId()), mById.get(ps.nodeId()), ps.role(),
                    availCountMap.getOrDefault(ps.nodeId(), 0)));
        }

        LearningPathVO vo = new LearningPathVO();
        vo.setPathId(path.getId());
        NodeRef target = new NodeRef();
        target.setNodeCode(nodeById.get(targetId).getNodeCode());
        target.setName(targetName);
        vo.setTargetNode(target);
        vo.setConclusionText(CONCLUSION_TEXT);
        vo.setSteps(steps);
        Progress pg = new Progress();
        pg.setDone(0);
        pg.setTotal(steps.size());
        vo.setProgress(pg);
        return vo;
    }

    @Override
    public LearningPathVO getCurrent(Long studentId, Long courseId) {
        StudentCourse sc = studentCourseMapper.selectOne(
                new LambdaQueryWrapper<StudentCourse>()
                        .eq(StudentCourse::getStudentId, studentId)
                        .eq(StudentCourse::getCourseId, courseId));
        if (sc == null || sc.getCurrentPathId() == null) {
            return emptyPath();
        }
        LearningPath path = learningPathMapper.selectById(sc.getCurrentPathId());
        if (path == null) {
            return emptyPath();
        }

        List<LearningPathItem> items = learningPathItemMapper.selectList(
                new LambdaQueryWrapper<LearningPathItem>()
                        .eq(LearningPathItem::getPathId, path.getId())
                        .orderByAsc(LearningPathItem::getStepOrder));

        List<KgNode> nodes = kgNodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .isNull(KgNode::getArchivedAt));
        Map<Long, KgNode> nodeById = new HashMap<>();
        for (KgNode n : nodes) {
            nodeById.put(n.getId(), n);
        }
        List<StudentMastery> rows = studentMasteryMapper.selectList(
                new LambdaQueryWrapper<StudentMastery>()
                        .eq(StudentMastery::getStudentId, studentId)
                        .eq(StudentMastery::getCourseId, courseId));
        Map<Long, StudentMastery> mById = new HashMap<>();
        for (StudentMastery m : rows) {
            mById.put(m.getNodeId(), m);
        }

        Set<Long> setNodes = new LinkedHashSet<>();
        for (LearningPathItem item : items) {
            setNodes.add(item.getNodeId());
        }
        List<KgEdge> edges = kgEdgeMapper.selectList(
                new LambdaQueryWrapper<KgEdge>()
                        .eq(KgEdge::getCourseId, courseId)
                        .eq(KgEdge::getRelationType, PREREQ)
                        .isNull(KgEdge::getArchivedAt));
        Map<Long, List<Long>> prereqMap = new HashMap<>();
        for (KgEdge e : edges) {
            prereqMap.computeIfAbsent(e.getToNodeId(), k -> new ArrayList<>()).add(e.getFromNodeId());
        }
        Set<Long> rootSet = rootNodeIds(setNodes, prereqMap);

        List<Long> stepNodeIds = items.stream()
                .map(LearningPathItem::getNodeId)
                .collect(Collectors.toList());
        Map<Long, Integer> availCountMap = buildAvailableCountMap(stepNodeIds);

        List<StepVO> steps = new ArrayList<>();
        int done = 0;
        for (LearningPathItem item : items) {
            String role = roleOf(item.getNodeId(), path.getTargetNodeId(), rootSet);
            steps.add(toStep(item, nodeById.get(item.getNodeId()), mById.get(item.getNodeId()), role,
                    availCountMap.getOrDefault(item.getNodeId(), 0)));
            if (item.getStatus() != null && item.getStatus() == ITEM_DONE) {
                done++;
            }
        }

        LearningPathVO vo = new LearningPathVO();
        vo.setPathId(path.getId());
        KgNode target = nodeById.get(path.getTargetNodeId());
        if (target != null) {
            NodeRef ref = new NodeRef();
            ref.setNodeCode(target.getNodeCode());
            ref.setName(target.getName());
            vo.setTargetNode(ref);
        }
        vo.setConclusionText(CONCLUSION_TEXT);
        vo.setSteps(steps);
        Progress pg = new Progress();
        pg.setDone(done);
        pg.setTotal(steps.size());
        vo.setProgress(pg);
        return vo;
    }

    @Override
    public void completeItem(Long itemId) {
        LearningPathItem item = learningPathItemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "学习步骤不存在：itemId=" + itemId);
        }
        LearningPath path = learningPathMapper.selectById(item.getPathId());
        AccessGuard.assertSelf(path == null ? null : path.getStudentId());
        if (item.getStatus() != null && item.getStatus() == ITEM_DONE) {
            return;
        }
        LearningPathItem upd = new LearningPathItem();
        upd.setId(itemId);
        upd.setStatus(ITEM_DONE);
        upd.setCompletedAt(java.time.LocalDateTime.now());
        learningPathItemMapper.updateById(upd);
    }

    private List<Long> topoSort(Set<Long> setNodes, Map<Long, List<Long>> prereqMap,
                                Map<Long, StudentMastery> mById) {
        Map<Long, Integer> indeg = new HashMap<>();
        for (Long n : setNodes) {
            int d = 0;
            for (Long p : prereqMap.getOrDefault(n, List.of())) {
                if (setNodes.contains(p)) {
                    d++;
                }
            }
            indeg.put(n, d);
        }
        Comparator<Long> weakestFirst = Comparator
                .comparingDouble((Long id) -> scoreOf(mById, id))
                .thenComparingLong(id -> id);
        List<Long> result = new ArrayList<>();
        List<Long> frontier = new ArrayList<>();
        for (Long n : setNodes) {
            if (indeg.get(n) == 0) {
                frontier.add(n);
            }
        }
        while (!frontier.isEmpty()) {
            frontier.sort(weakestFirst);
            Long cur = frontier.remove(0);
            result.add(cur);
            for (Long n : setNodes) {
                if (prereqMap.getOrDefault(n, List.of()).contains(cur) && indeg.get(n) > 0) {
                    indeg.put(n, indeg.get(n) - 1);
                    if (indeg.get(n) == 0) {
                        frontier.add(n);
                    }
                }
            }
        }
        if (result.size() < setNodes.size()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "学习路径存在环，无法生成有序步骤");
        }
        return result;
    }

    private Set<Long> rootNodeIds(Set<Long> setNodes, Map<Long, List<Long>> prereqMap) {
        Set<Long> roots = new HashSet<>();
        for (Long n : setNodes) {
            boolean hasPrereqInSet = false;
            for (Long p : prereqMap.getOrDefault(n, List.of())) {
                if (setNodes.contains(p)) {
                    hasPrereqInSet = true;
                    break;
                }
            }
            if (!hasPrereqInSet) {
                roots.add(n);
            }
        }
        return roots;
    }

    private String roleOf(Long id, Long targetId, Set<Long> rootSet) {
        if (id.equals(targetId)) {
            return "stuck";
        }
        return rootSet.contains(id) ? "root" : "prereq";
    }

    private String resolveReason(String nodeName, String role, String targetName, boolean useAi) {
        if (useAi) {
            try {
                String label = roleLabel(role);
                String ai = questionAiClient.explainLearningStep(nodeName, label, targetName);
                if (ai != null && !ai.isBlank()) {
                    return ai;
                }
            } catch (Exception ignore) {
                // 回退模板
            }
        }
        return templateReason(role, targetName);
    }

    private String templateReason(String role, String targetName) {
        return switch (role) {
            case "root" -> "这是回溯链的根基，最该先打通，后面每一步才稳。";
            case "stuck" -> "这正是诊断发现的卡点本身，前置打通后再回来会顺手得多。";
            default -> "突破「" + targetName + "」要用到它，先补这一步。";
        };
    }

    private String roleLabel(String role) {
        return switch (role) {
            case "root" -> "根因";
            case "stuck" -> "卡点";
            default -> "前置";
        };
    }

    private boolean isMastered(Map<Long, StudentMastery> mById, Long id) {
        StudentMastery m = mById.get(id);
        if (m == null || m.getMasteryScore() == null) {
            return false;
        }
        return decayCalculator.effectiveScoreOf(m) >= decayCalculator.getMasteredThreshold();
    }

    private double scoreOf(Map<Long, StudentMastery> mById, Long id) {
        StudentMastery m = mById.get(id);
        return (m == null || m.getMasteryScore() == null) ? 0.0 : m.getMasteryScore().doubleValue();
    }

    private StepVO toStep(LearningPathItem item, KgNode node, StudentMastery m, String role,
                          int availableQuestionCount) {
        StepVO s = new StepVO();
        s.setItemId(item.getId());
        if (node != null) {
            s.setNodeId(node.getId());
            s.setNodeCode(node.getNodeCode());
            s.setName(node.getName());
            s.setChapter(node.getChapter());
        }
        s.setMasteryScore(m == null || m.getMasteryScore() == null ? null : m.getMasteryScore().doubleValue());
        s.setMasteryLevel(m == null ? null : m.getMasteryLevel());
        s.setStepOrder(item.getStepOrder() == null ? 0 : item.getStepOrder());
        s.setStatus(item.getStatus() == null ? 0 : item.getStatus());
        s.setCompletedAt(item.getCompletedAt());
        s.setReason(item.getReason());
        s.setRole(role);
        s.setAvailableQuestionCount(availableQuestionCount);
        return s;
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

    private LearningPathVO emptyPath() {
        LearningPathVO vo = new LearningPathVO();
        vo.setSteps(List.of());
        Progress pg = new Progress();
        pg.setDone(0);
        pg.setTotal(0);
        vo.setProgress(pg);
        return vo;
    }

}
