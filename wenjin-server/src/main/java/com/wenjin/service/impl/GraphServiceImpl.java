package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.GraphDataVO;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.dto.GraphImportResult;
import com.wenjin.dto.GraphValidateResult;
import com.wenjin.entity.Course;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.security.CourseMutationLock;
import com.wenjin.service.GraphService;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.support.PrerequisiteCycleDetector;
import com.wenjin.support.RelationType;
import com.wenjin.support.ReviewMarker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图谱服务实现。导入流程：归属校验 → 结构校验 → 按 (course_id, node_code) 差量对账落库。
 * <p>节点主键在重导入时保持不变；移除的节点/边软归档（archived_at），不硬删。
 */
@Service
public class GraphServiceImpl implements GraphService {

    private static final Logger log = LoggerFactory.getLogger(GraphServiceImpl.class);

    /** 掌握度三态级别串：已掌握 / 薄弱 / 未学（无 studentId 或无掌握行时统一作未学） */
    private static final String MASTERY_MASTERED = "mastered";
    private static final String MASTERY_WEAK = "weak";
    private static final String MASTERY_UNLEARNED = "unlearned";

    private final CourseMapper courseMapper;
    private final KgNodeMapper nodeMapper;
    private final KgEdgeMapper edgeMapper;
    private final StudentMasteryMapper studentMasteryMapper;
    private final MasteryDecayCalculator decayCalculator;
    private final CourseAccessService courseAccessService;
    private final CourseMutationLock courseMutationLock;
    private final FeatureFlagsProperties featureFlags;

    public GraphServiceImpl(CourseMapper courseMapper, KgNodeMapper nodeMapper,
                            KgEdgeMapper edgeMapper, StudentMasteryMapper studentMasteryMapper,
                            MasteryDecayCalculator decayCalculator,
                            CourseAccessService courseAccessService,
                            CourseMutationLock courseMutationLock,
                            FeatureFlagsProperties featureFlags) {
        this.courseMapper = courseMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.studentMasteryMapper = studentMasteryMapper;
        this.decayCalculator = decayCalculator;
        this.courseAccessService = courseAccessService;
        this.courseMutationLock = courseMutationLock;
        this.featureFlags = featureFlags;
    }

    // ───────────────────────────── 导入 ─────────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GraphImportResult importGraph(String courseCode, GraphImportRequest request) {
        ensureGraphReconcileEnabled();

        if (!StringUtils.hasText(courseCode)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "courseCode 不能为空");
        }
        if (request == null || request.getNodes() == null || request.getNodes().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "图谱数据为空：nodes 不能为空");
        }

        // 0) 归属：课程须已存在且当前教师可管理（跨教师 / 不存在统一 404）
        Long courseId = courseAccessService.assertManageCourseCode(courseCode);
        courseMutationLock.lockActive(courseId);
        courseAccessService.assertManageCourse(courseId);

        // 1) 校验：任一不通过则整体拒绝，返回逐条明细
        GraphValidateResult vr = validate(request);
        if (!vr.isValid()) {
            String summary = "图谱校验失败，共 " + vr.getIssues().size() + " 处问题，已整体拒绝导入";
            log.warn("{}：{}", summary, vr.getIssues());
            throw new BusinessException(ResultCode.GRAPH_VALIDATE_FAIL, summary, vr);
        }

        // 2) 定位课程（assert 已保证存在与归属）
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
        boolean courseDirty = false;
        if (request.getCourse() != null && StringUtils.hasText(request.getCourse().getName())) {
            course.setName(request.getCourse().getName());
            courseDirty = true;
        }
        // 重导入解除课程归档
        if (course.getArchivedAt() != null) {
            course.setArchivedAt(null);
            courseDirty = true;
        }
        if (courseDirty) {
            if (course.getArchivedAt() == null) {
                // updateById 忽略 null：用 UpdateWrapper 清 archived_at
                courseMapper.update(null, new UpdateWrapper<Course>()
                        .eq("id", courseId)
                        .set("name", course.getName())
                        .set("archived_at", null));
            } else {
                courseMapper.updateById(course);
            }
        }

        // 3) 差量对账节点：按 (course_id, node_code) 保主键
        Map<String, Long> codeToId = reconcileNodes(courseId, request.getNodes());

        // 4) 差量对账边：按 (course_id, from_node_id, to_node_id, relation_type)
        int edgeCount = reconcileEdges(courseId, request.getEdges(), codeToId);

        int nodeCount = request.getNodes().size();
        log.info("图谱差量导入成功：courseCode={}, courseId={}, 节点={}, 边={}",
                courseCode, courseId, nodeCount, edgeCount);
        return new GraphImportResult(courseId, courseCode, course.getName(), nodeCount, edgeCount);
    }

    /**
     * 节点差量：存在则更新可变字段并解除归档；不存在则插入；库内多余节点归档。
     *
     * @return node_code → kg_node.id（含本次生效节点）
     */
    private Map<String, Long> reconcileNodes(Long courseId, List<GraphImportRequest.NodeItem> items) {
        List<KgNode> existing = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>().eq(KgNode::getCourseId, courseId));
        Map<String, KgNode> byCode = new HashMap<>();
        for (KgNode n : existing) {
            byCode.put(n.getNodeCode(), n);
        }

        Set<String> seen = new HashSet<>();
        Map<String, Long> codeToId = new HashMap<>();
        for (GraphImportRequest.NodeItem item : items) {
            String code = item.getId().trim();
            seen.add(code);
            KgNode row = byCode.get(code);
            if (row == null) {
                KgNode node = new KgNode();
                node.setCourseId(courseId);
                applyNodeFields(node, item);
                nodeMapper.insert(node);
                codeToId.put(code, node.getId());
            } else {
                applyNodeFields(row, item);
                // 清归档：updateById 忽略 null，用 UpdateWrapper
                nodeMapper.update(null, new UpdateWrapper<KgNode>()
                        .eq("id", row.getId())
                        .set("name", row.getName())
                        .set("chapter", row.getChapter())
                        .set("difficulty", row.getDifficulty())
                        .set("is_key", row.getIsKey())
                        .set("bloom", row.getBloom())
                        .set("description", row.getDescription())
                        .set("node_note", row.getNodeNote())
                        .set("archived_at", null));
                codeToId.put(code, row.getId());
            }
        }

        LocalDateTime now = LocalDateTime.now();
        for (KgNode leftover : existing) {
            if (!seen.contains(leftover.getNodeCode()) && leftover.getArchivedAt() == null) {
                leftover.setArchivedAt(now);
                nodeMapper.updateById(leftover);
            }
        }
        return codeToId;
    }

    private void applyNodeFields(KgNode node, GraphImportRequest.NodeItem n) {
        node.setNodeCode(n.getId().trim());
        node.setName(n.getName());
        node.setChapter(n.getChapter());
        node.setDifficulty(n.getDifficulty() == null ? 1 : n.getDifficulty());
        node.setIsKey(Boolean.TRUE.equals(n.getIsKey()) ? 1 : 0);
        node.setBloom(n.getBloom());
        node.setDescription(n.getDescription());
        node.setNodeNote(n.getNote());
        node.setArchivedAt(null);
    }

    /**
     * 边差量：存在则更新备注并解除归档；不存在则插入；库内多余边归档。
     */
    private int reconcileEdges(Long courseId, List<GraphImportRequest.EdgeItem> edgeItems,
                               Map<String, Long> codeToId) {
        List<KgEdge> existing = edgeMapper.selectList(
                new LambdaQueryWrapper<KgEdge>().eq(KgEdge::getCourseId, courseId));
        // key: fromId|toId|type
        Map<String, KgEdge> byKey = new HashMap<>();
        for (KgEdge e : existing) {
            byKey.put(edgeKey(e.getFromNodeId(), e.getToNodeId(), e.getRelationType()), e);
        }

        Set<String> seen = new HashSet<>();
        int edgeCount = 0;
        if (edgeItems != null) {
            for (GraphImportRequest.EdgeItem item : edgeItems) {
                Long fromId = codeToId.get(item.getSource().trim());
                Long toId = codeToId.get(item.getTarget().trim());
                RelationType rt = RelationType.fromLabel(item.getType());
                int typeCode = rt.getCode();
                String key = edgeKey(fromId, toId, typeCode);
                seen.add(key);
                KgEdge row = byKey.get(key);
                if (row == null) {
                    KgEdge edge = new KgEdge();
                    edge.setCourseId(courseId);
                    edge.setFromNodeId(fromId);
                    edge.setToNodeId(toId);
                    edge.setRelationType(typeCode);
                    edge.setRelationNote(item.getNote());
                    edgeMapper.insert(edge);
                    // 防御：若校验漏掉同键重复，后续同键走更新而非二次 insert（避免 UK 500）
                    byKey.put(key, edge);
                } else {
                    edgeMapper.update(null, new UpdateWrapper<KgEdge>()
                            .eq("id", row.getId())
                            .set("relation_note", item.getNote())
                            .set("archived_at", null));
                }
                edgeCount++;
            }
        }

        LocalDateTime now = LocalDateTime.now();
        for (KgEdge leftover : existing) {
            String key = edgeKey(leftover.getFromNodeId(), leftover.getToNodeId(), leftover.getRelationType());
            if (!seen.contains(key) && leftover.getArchivedAt() == null) {
                leftover.setArchivedAt(now);
                edgeMapper.updateById(leftover);
            }
        }
        return edgeCount;
    }

    private static String edgeKey(Long fromId, Long toId, Integer type) {
        return fromId + "|" + toId + "|" + type;
    }

    private void ensureGraphReconcileEnabled() {
        if (featureFlags == null || !featureFlags.isGraphReconcileEnabled()) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "图谱对账/导入功能暂未开放");
        }
    }

    // ───────────────────────────── 校验 ─────────────────────────────

    /**
     * 执行三类校验并汇总所有问题（不在首个错误处中断，便于一次性反馈）：
     *   a. 节点 ID 唯一（且非空）
     *   b. 所有边的 source/target 均存在于节点集（且关系类型合法）
     *   c. 「前置」类型边做拓扑排序，存在环则报出环路径
     */
    private GraphValidateResult validate(GraphImportRequest request) {
        GraphValidateResult vr = new GraphValidateResult();

        // a. 节点 ID 唯一性 + 必填
        Set<String> nodeIds = new HashSet<>();
        Set<String> duplicated = new LinkedHashSet<>();
        for (int i = 0; i < request.getNodes().size(); i++) {
            GraphImportRequest.NodeItem n = request.getNodes().get(i);
            String id = n.getId() == null ? null : n.getId().trim();
            if (!StringUtils.hasText(id)) {
                vr.add("EMPTY", "第 " + (i + 1) + " 个节点缺少 id");
                continue;
            }
            if (!StringUtils.hasText(n.getName())) {
                vr.add("EMPTY", "节点 " + id + " 缺少 name");
            }
            if (!nodeIds.add(id)) {
                duplicated.add(id);
            }
        }
        for (String dup : duplicated) {
            vr.add("DUPLICATE_NODE_ID", "节点 ID 重复：" + dup);
        }

        // b. 边端点存在性 + 关系类型合法性 + 业务键（source|target|type）唯一
        List<GraphImportRequest.EdgeItem> edges = request.getEdges() == null
                ? List.of() : request.getEdges();
        Set<String> edgeKeys = new HashSet<>();
        Set<String> duplicatedEdges = new LinkedHashSet<>();
        for (int i = 0; i < edges.size(); i++) {
            GraphImportRequest.EdgeItem e = edges.get(i);
            String src = e.getSource() == null ? null : e.getSource().trim();
            String tgt = e.getTarget() == null ? null : e.getTarget().trim();
            int line = i + 1;
            if (!StringUtils.hasText(src) || !nodeIds.contains(src)) {
                vr.add("MISSING_NODE", "第 " + line + " 条边的 source「" + e.getSource() + "」在节点集中不存在");
            }
            if (!StringUtils.hasText(tgt) || !nodeIds.contains(tgt)) {
                vr.add("MISSING_NODE", "第 " + line + " 条边的 target「" + e.getTarget() + "」在节点集中不存在");
            }
            RelationType rt = RelationType.fromLabel(e.getType());
            if (rt == null) {
                vr.add("BAD_RELATION_TYPE", "第 " + line + " 条边的关系类型「" + e.getType()
                        + "」非法（仅允许：前置/包含/相关/应用）");
            } else if (StringUtils.hasText(src) && StringUtils.hasText(tgt)) {
                // 与 reconcileEdges 业务键一致：source|target|typeCode（payload 侧用编码）
                String bizKey = src + "|" + tgt + "|" + rt.getCode();
                if (!edgeKeys.add(bizKey)) {
                    duplicatedEdges.add(src + " → " + tgt + "（" + e.getType() + "）");
                }
            }
        }
        for (String dup : duplicatedEdges) {
            vr.add("DUPLICATE_EDGE", "边重复：" + dup);
        }

        // c. 「前置」边环检测（仅对端点均存在的前置边构图）
        List<String> cycle = detectPrerequisiteCycle(edges, nodeIds);
        if (cycle != null) {
            vr.add("CYCLE", "「前置」关系存在环路：" + String.join(" → ", cycle));
        }

        return vr;
    }

    /**
     * 对「前置」边构成的有向图做环检测（复用 {@link PrerequisiteCycleDetector}）。
     *
     * @return 第一条检出的环路径（节点编码序列，首尾相接），无环返回 null
     */
    private List<String> detectPrerequisiteCycle(List<GraphImportRequest.EdgeItem> edges, Set<String> nodeIds) {
        Map<String, List<String>> adj = new HashMap<>();
        for (GraphImportRequest.EdgeItem e : edges) {
            if (RelationType.fromLabel(e.getType()) != RelationType.PREREQUISITE) {
                continue;
            }
            String src = e.getSource() == null ? null : e.getSource().trim();
            String tgt = e.getTarget() == null ? null : e.getTarget().trim();
            if (src == null || tgt == null || !nodeIds.contains(src) || !nodeIds.contains(tgt)) {
                continue;
            }
            adj.computeIfAbsent(src, k -> new ArrayList<>()).add(tgt);
        }
        return PrerequisiteCycleDetector.detect(adj);
    }

    // ───────────────────────────── 查询 ─────────────────────────────

    @Override
    public GraphDataVO getGraph(Long courseId) {
        return getGraph(courseId, null);
    }

    @Override
    public GraphDataVO getGraph(Long courseId, Long studentId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null || course.getArchivedAt() != null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在：courseId=" + courseId);
        }

        List<KgNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .isNull(KgNode::getArchivedAt)
                        .orderByAsc(KgNode::getId));
        List<KgEdge> edges = edgeMapper.selectList(
                new LambdaQueryWrapper<KgEdge>()
                        .eq(KgEdge::getCourseId, courseId)
                        .isNull(KgEdge::getArchivedAt)
                        .orderByAsc(KgEdge::getId));

        Map<Long, String> idToCode = new HashMap<>();
        for (KgNode n : nodes) {
            idToCode.put(n.getId(), n.getNodeCode());
        }

        Map<Long, StudentMastery> masteryByNode = new HashMap<>();
        if (studentId != null) {
            List<StudentMastery> rows = studentMasteryMapper.selectList(
                    new LambdaQueryWrapper<StudentMastery>()
                            .eq(StudentMastery::getStudentId, studentId)
                            .eq(StudentMastery::getCourseId, courseId));
            for (StudentMastery sm : rows) {
                masteryByNode.put(sm.getNodeId(), sm);
            }
        }

        GraphDataVO vo = new GraphDataVO();

        GraphDataVO.CourseVO courseVO = new GraphDataVO.CourseVO();
        courseVO.setId(course.getId());
        courseVO.setCode(course.getCode());
        courseVO.setName(course.getName());
        vo.setCourse(courseVO);

        List<GraphDataVO.NodeVO> nodeVOs = new ArrayList<>(nodes.size());
        for (KgNode n : nodes) {
            GraphDataVO.NodeVO nv = new GraphDataVO.NodeVO();
            nv.setNodeCode(n.getNodeCode());
            nv.setName(n.getName());
            nv.setChapter(n.getChapter());
            nv.setDifficulty(n.getDifficulty());
            nv.setIsKey(n.getIsKey() != null && n.getIsKey() == 1);
            nv.setDescription(n.getDescription());

            StudentMastery sm = masteryByNode.get(n.getId());
            if (sm != null && sm.getMasteryScore() != null) {
                applyMasteryWithDecay(nv, sm);
            } else if (sm != null) {
                nv.setMastery(levelToMastery(sm.getMasteryLevel()));
            } else {
                nv.setMastery(MASTERY_UNLEARNED);
            }
            nodeVOs.add(nv);
        }
        vo.setNodes(nodeVOs);

        List<GraphDataVO.EdgeVO> edgeVOs = new ArrayList<>(edges.size());
        for (KgEdge e : edges) {
            if (ReviewMarker.isPending(e.getRelationNote())) continue;
            GraphDataVO.EdgeVO ev = new GraphDataVO.EdgeVO();
            ev.setSource(idToCode.get(e.getFromNodeId()));
            ev.setTarget(idToCode.get(e.getToNodeId()));
            ev.setType(RelationType.labelOf(e.getRelationType()));
            edgeVOs.add(ev);
        }
        vo.setEdges(edgeVOs);

        return vo;
    }

    /**
     * 读时填充掌握度双轨字段：raw 来自库，effective 由衰减模型计算（不写回 student_mastery）。
     */
    private void applyMasteryWithDecay(GraphDataVO.NodeVO nv, StudentMastery sm) {
        double raw = sm.getMasteryScore().doubleValue();
        double days = decayCalculator.daysSinceEvidence(sm.getUpdatedAt());
        double effective = decayCalculator.effectiveScoreOf(sm, days);
        int effectiveLevel = decayCalculator.effectiveLevelOf(effective);
        double threshold = decayCalculator.getMasteredThreshold();

        nv.setRawScore(raw);
        nv.setMasteryScore(raw);
        nv.setEffectiveScore(effective);
        nv.setEffectiveLevel(effectiveLevel);
        nv.setDaysSinceEvidence(days);
        nv.setMastery(levelToMastery(effectiveLevel));
        nv.setDecaying(raw >= threshold && effective < threshold);
    }

    /** 掌握等级 TINYINT → 前端三态串：2=已掌握 / 1=薄弱 / 0 及空值或未知=未学。 */
    private String levelToMastery(Integer level) {
        if (level == null) {
            return MASTERY_UNLEARNED;
        }
        return level == 2 ? MASTERY_MASTERED : level == 1 ? MASTERY_WEAK : MASTERY_UNLEARNED;
    }
}
