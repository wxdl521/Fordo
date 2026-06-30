package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.LearningPathItemMapper;
import com.wenjin.mapper.LearningPathMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.DiagnosticResultService;
import com.wenjin.service.PathService;
import com.wenjin.support.RelationType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

/**
 * 学习路径服务实现（PRD 8.3）。
 * <p>聚焦卡点：从 target 沿前置边逆向 BFS 收集未掌握(薄弱+未学)前置（遇已掌握剪枝），
 * 对节点集做 Kahn 拓扑排序（同层按掌握度升序），落库 learning_path(+item)。
 */
@Service
public class PathServiceImpl implements PathService {

    private static final int PREREQ = RelationType.PREREQUISITE.getCode();
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_INACTIVE = 0;
    private static final int ITEM_PENDING = 0;
    private static final int ITEM_DONE = 1;
    private static final String CONCLUSION_TEXT = "从根因补起，沿前置链逐步推进——根基稳了，卡点自然松动。";

    private final StudentMasteryMapper studentMasteryMapper;
    private final KgNodeMapper kgNodeMapper;
    private final KgEdgeMapper kgEdgeMapper;
    private final LearningPathMapper learningPathMapper;
    private final LearningPathItemMapper learningPathItemMapper;
    private final DiagnosticResultService diagnosticResultService;
    private final QuestionAiClient questionAiClient;

    @Value("${wenjin.mastery.mastered-threshold:75}")
    private double masteredThreshold;

    public PathServiceImpl(StudentMasteryMapper studentMasteryMapper, KgNodeMapper kgNodeMapper,
                           KgEdgeMapper kgEdgeMapper, LearningPathMapper learningPathMapper,
                           LearningPathItemMapper learningPathItemMapper,
                           DiagnosticResultService diagnosticResultService,
                           QuestionAiClient questionAiClient) {
        this.studentMasteryMapper = studentMasteryMapper;
        this.kgNodeMapper = kgNodeMapper;
        this.kgEdgeMapper = kgEdgeMapper;
        this.learningPathMapper = learningPathMapper;
        this.learningPathItemMapper = learningPathItemMapper;
        this.diagnosticResultService = diagnosticResultService;
        this.questionAiClient = questionAiClient;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LearningPathVO generate(PathGenerateRequest req) {
        Long studentId = req.getStudentId();
        Long courseId = req.getCourseId();

        List<KgNode> nodes = kgNodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>().eq(KgNode::getCourseId, courseId));
        Map<Long, KgNode> nodeById = new HashMap<>();
        Map<String, Long> idByCode = new HashMap<>();
        for (KgNode n : nodes) {
            nodeById.put(n.getId(), n);
            idByCode.put(n.getNodeCode(), n.getId());
        }

        // 解析 target
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
                        .eq(KgEdge::getRelationType, PREREQ));
        Map<Long, List<Long>> prereqMap = new HashMap<>(); // toNodeId -> [fromNodeId]
        for (KgEdge e : edges) {
            prereqMap.computeIfAbsent(e.getToNodeId(), k -> new ArrayList<>()).add(e.getFromNodeId());
        }

        // 反向 BFS 收集未掌握闭包（已掌握剪枝），含 target
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

        // Kahn 拓扑排序（子图内部前置边），同层按掌握度升序、nodeId 升序
        List<Long> ordered = topoSort(setNodes, prereqMap, mById);
        // 根因 = 子图内无未掌握前置者（入度 0），可能多个；非仅拓扑首个
        Set<Long> rootSet = rootNodeIds(setNodes, prereqMap);

        // 持久化：旧路径失效。
        // 注意：此处用字符串列名 UpdateWrapper 而非 LambdaUpdateWrapper——后者的 set() 会即时解析
        // 实体 lambda 列缓存（TableInfo），该缓存依赖 Spring/MyBatis-Plus 启动初始化，纯 Mockito
        // 单测下不存在，会抛 "can not find lambda cache"。列名与 schema 一致，运行时等价。
        learningPathMapper.update(null, new UpdateWrapper<LearningPath>()
                .eq("student_id", studentId)
                .eq("course_id", courseId)
                .eq("status", STATUS_ACTIVE)
                .set("status", STATUS_INACTIVE));

        LearningPath path = new LearningPath();
        path.setStudentId(studentId);
        path.setCourseId(courseId);
        path.setTargetNodeId(targetId);
        path.setStatus(STATUS_ACTIVE);
        path.setGeneratedAt(LocalDateTime.now());
        learningPathMapper.insert(path);

        String targetName = nodeById.get(targetId).getName();
        List<StepVO> steps = new ArrayList<>();
        int order = 1;
        for (Long id : ordered) {
            String role = roleOf(id, targetId, rootSet);
            String reason = resolveReason(nodeById.get(id).getName(), role, targetName, req.isUseAi());

            LearningPathItem item = new LearningPathItem();
            item.setPathId(path.getId());
            item.setNodeId(id);
            item.setStepOrder(order);
            item.setStatus(ITEM_PENDING);
            item.setReason(reason);
            learningPathItemMapper.insert(item);

            steps.add(toStep(item, nodeById.get(id), mById.get(id), role));
            order++;
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
        List<LearningPath> active = learningPathMapper.selectList(
                new LambdaQueryWrapper<LearningPath>()
                        .eq(LearningPath::getStudentId, studentId)
                        .eq(LearningPath::getCourseId, courseId)
                        .eq(LearningPath::getStatus, STATUS_ACTIVE)
                        .orderByDesc(LearningPath::getId));
        if (active.isEmpty()) {
            return emptyPath();
        }
        LearningPath path = active.get(0);

        List<LearningPathItem> items = learningPathItemMapper.selectList(
                new LambdaQueryWrapper<LearningPathItem>()
                        .eq(LearningPathItem::getPathId, path.getId())
                        .orderByAsc(LearningPathItem::getStepOrder));

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
        Map<Long, StudentMastery> mById = new HashMap<>();
        for (StudentMastery m : rows) {
            mById.put(m.getNodeId(), m);
        }

        // 重建角色：从落库步骤节点集 + 前置边算子图入度，与 generate 一致判定 root（可能多个）
        Set<Long> setNodes = new LinkedHashSet<>();
        for (LearningPathItem item : items) {
            setNodes.add(item.getNodeId());
        }
        List<KgEdge> edges = kgEdgeMapper.selectList(
                new LambdaQueryWrapper<KgEdge>()
                        .eq(KgEdge::getCourseId, courseId)
                        .eq(KgEdge::getRelationType, PREREQ));
        Map<Long, List<Long>> prereqMap = new HashMap<>();
        for (KgEdge e : edges) {
            prereqMap.computeIfAbsent(e.getToNodeId(), k -> new ArrayList<>()).add(e.getFromNodeId());
        }
        Set<Long> rootSet = rootNodeIds(setNodes, prereqMap);

        List<StepVO> steps = new ArrayList<>();
        int done = 0;
        for (LearningPathItem item : items) {
            String role = roleOf(item.getNodeId(), path.getTargetNodeId(), rootSet);
            steps.add(toStep(item, nodeById.get(item.getNodeId()), mById.get(item.getNodeId()), role));
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
        // 归属校验：item 只带 itemId，须经 path 反查出属主 studentId，绑定当前登录用户，
        // 否则任意登录者可标记他人路径步骤为完成。path 缺失按 null 处理 → assertSelf 抛 FORBIDDEN。
        LearningPath path = learningPathMapper.selectById(item.getPathId());
        AccessGuard.assertSelf(path == null ? null : path.getStudentId());
        if (item.getStatus() != null && item.getStatus() == ITEM_DONE) {
            return; // 幂等
        }
        LearningPathItem upd = new LearningPathItem();
        upd.setId(itemId);
        upd.setStatus(ITEM_DONE);
        upd.setCompletedAt(LocalDateTime.now());
        learningPathItemMapper.updateById(upd);
    }

    // ── 拓扑排序 ───────────────────────────────────────────
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
            // cur 是某些节点的前置 → 解开依赖：遍历 setNodes 中以 cur 为前置者
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

    /** 子图内无未掌握前置者（入度 0）的集合——即「根因」候选，可能多个。 */
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

    /** 角色：卡点本身=stuck（优先）；子图入度 0=root；其余=prereq。 */
    private String roleOf(Long id, Long targetId, Set<Long> rootSet) {
        if (id.equals(targetId)) {
            return "stuck";
        }
        return rootSet.contains(id) ? "root" : "prereq";
    }

    // ── reason ─────────────────────────────────────────────
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

    // ── 工具 ───────────────────────────────────────────────
    private boolean isMastered(Map<Long, StudentMastery> mById, Long id) {
        StudentMastery m = mById.get(id);
        return m != null && m.getMasteryScore() != null
                && m.getMasteryScore().doubleValue() >= masteredThreshold;
    }

    private double scoreOf(Map<Long, StudentMastery> mById, Long id) {
        StudentMastery m = mById.get(id);
        return (m == null || m.getMasteryScore() == null) ? 0.0 : m.getMasteryScore().doubleValue();
    }

    private StepVO toStep(LearningPathItem item, KgNode node, StudentMastery m, String role) {
        StepVO s = new StepVO();
        s.setItemId(item.getId());
        if (node != null) {
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
        return s;
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
