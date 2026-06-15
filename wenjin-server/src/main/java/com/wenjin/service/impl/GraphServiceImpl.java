package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
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
import com.wenjin.service.GraphService;
import com.wenjin.support.RelationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图谱服务实现。导入流程：校验（节点ID唯一 / 边端点存在 / 前置环检测）→ 全量替换落库。
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

    /** 演示教师ID（新建课程时作为创建者） */
    @Value("${wenjin.demo.teacher-id:1}")
    private Long demoTeacherId;

    public GraphServiceImpl(CourseMapper courseMapper, KgNodeMapper nodeMapper,
                            KgEdgeMapper edgeMapper, StudentMasteryMapper studentMasteryMapper) {
        this.courseMapper = courseMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.studentMasteryMapper = studentMasteryMapper;
    }

    // ───────────────────────────── 导入 ─────────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GraphImportResult importGraph(String courseCode, GraphImportRequest request) {
        if (!StringUtils.hasText(courseCode)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "courseCode 不能为空");
        }
        if (request == null || request.getNodes() == null || request.getNodes().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "图谱数据为空：nodes 不能为空");
        }

        // 1) 校验：任一不通过则整体拒绝，返回逐条明细
        GraphValidateResult vr = validate(request);
        if (!vr.isValid()) {
            String summary = "图谱校验失败，共 " + vr.getIssues().size() + " 处问题，已整体拒绝导入";
            log.warn("{}：{}", summary, vr.getIssues());
            throw new BusinessException(ResultCode.GRAPH_VALIDATE_FAIL, summary, vr);
        }

        // 2) 定位/创建课程（按业务编码）
        Course course = courseMapper.selectOne(
                new LambdaQueryWrapper<Course>().eq(Course::getCode, courseCode));
        if (course == null) {
            course = new Course();
            course.setCode(courseCode);
            course.setName(resolveCourseName(request, courseCode));
            course.setTeacherId(demoTeacherId);
            course.setStatus(1);
            courseMapper.insert(course);
        } else if (request.getCourse() != null && StringUtils.hasText(request.getCourse().getName())) {
            // 已存在则同步课程名（轻量更新）
            course.setName(request.getCourse().getName());
            courseMapper.updateById(course);
        }
        Long courseId = course.getId();

        // 3) 全量替换：先删后插（同一事务内，先删边后删点）
        edgeMapper.delete(new LambdaQueryWrapper<KgEdge>().eq(KgEdge::getCourseId, courseId));
        nodeMapper.delete(new LambdaQueryWrapper<KgNode>().eq(KgNode::getCourseId, courseId));

        // 4) 插入节点，记录 业务编码 -> 新主键 映射
        Map<String, Long> codeToId = new HashMap<>();
        for (GraphImportRequest.NodeItem n : request.getNodes()) {
            KgNode node = new KgNode();
            node.setCourseId(courseId);
            node.setNodeCode(n.getId().trim());
            node.setName(n.getName());
            node.setChapter(n.getChapter());
            node.setDifficulty(n.getDifficulty() == null ? 1 : n.getDifficulty());
            node.setIsKey(Boolean.TRUE.equals(n.getIsKey()) ? 1 : 0);
            node.setBloom(n.getBloom());
            node.setDescription(n.getDescription());
            node.setNodeNote(n.getNote());
            nodeMapper.insert(node);
            codeToId.put(node.getNodeCode(), node.getId());
        }

        // 5) 插入边（关系类型中文标签 -> TINYINT 编码）
        int edgeCount = 0;
        if (request.getEdges() != null) {
            for (GraphImportRequest.EdgeItem e : request.getEdges()) {
                KgEdge edge = new KgEdge();
                edge.setCourseId(courseId);
                edge.setFromNodeId(codeToId.get(e.getSource().trim()));
                edge.setToNodeId(codeToId.get(e.getTarget().trim()));
                RelationType rt = RelationType.fromLabel(e.getType());
                edge.setRelationType(rt.getCode());
                edge.setRelationNote(e.getNote());
                edgeMapper.insert(edge);
                edgeCount++;
            }
        }

        int nodeCount = request.getNodes().size();
        log.info("图谱导入成功：courseCode={}, courseId={}, 节点={}, 边={}",
                courseCode, courseId, nodeCount, edgeCount);
        return new GraphImportResult(courseId, courseCode, course.getName(), nodeCount, edgeCount);
    }

    private String resolveCourseName(GraphImportRequest request, String courseCode) {
        if (request.getCourse() != null && StringUtils.hasText(request.getCourse().getName())) {
            return request.getCourse().getName();
        }
        return "课程-" + courseCode;
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

        // b. 边端点存在性 + 关系类型合法性
        List<GraphImportRequest.EdgeItem> edges = request.getEdges() == null
                ? List.of() : request.getEdges();
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
            if (RelationType.fromLabel(e.getType()) == null) {
                vr.add("BAD_RELATION_TYPE", "第 " + line + " 条边的关系类型「" + e.getType()
                        + "」非法（仅允许：前置/包含/相关/应用）");
            }
        }

        // c. 「前置」边环检测（仅对端点均存在的前置边构图）
        List<String> cycle = detectPrerequisiteCycle(edges, nodeIds);
        if (cycle != null) {
            vr.add("CYCLE", "「前置」关系存在环路：" + String.join(" → ", cycle));
        }

        return vr;
    }

    /**
     * 对「前置」边构成的有向图做环检测（DFS 三色标记）。
     *
     * @return 第一条检出的环路径（节点编码序列，首尾相接），无环返回 null
     */
    private List<String> detectPrerequisiteCycle(List<GraphImportRequest.EdgeItem> edges, Set<String> nodeIds) {
        // 构建邻接表：仅取「前置」且两端都在节点集内的边
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

        // 0=未访问, 1=在当前递归栈中, 2=已完成
        Map<String, Integer> state = new HashMap<>();
        List<String> stack = new ArrayList<>();
        for (String start : adj.keySet()) {
            if (state.getOrDefault(start, 0) == 0) {
                List<String> cycle = dfs(start, adj, state, stack);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        return null;
    }

    private List<String> dfs(String node, Map<String, List<String>> adj,
                             Map<String, Integer> state, List<String> stack) {
        state.put(node, 1);
        stack.add(node);
        for (String next : adj.getOrDefault(node, List.of())) {
            Integer st = state.getOrDefault(next, 0);
            if (st == 1) {
                // 找到环：从栈中 next 第一次出现处截取，并补上 next 形成闭环
                int idx = stack.indexOf(next);
                List<String> cycle = new ArrayList<>(stack.subList(idx, stack.size()));
                cycle.add(next);
                return cycle;
            }
            if (st == 0) {
                List<String> cycle = dfs(next, adj, state, stack);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        stack.remove(stack.size() - 1);
        state.put(node, 2);
        return null;
    }

    // ───────────────────────────── 查询 ─────────────────────────────

    @Override
    public GraphDataVO getGraph(Long courseId) {
        return getGraph(courseId, null);
    }

    @Override
    public GraphDataVO getGraph(Long courseId, Long studentId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在：courseId=" + courseId);
        }

        List<KgNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>().eq(KgNode::getCourseId, courseId).orderByAsc(KgNode::getId));
        List<KgEdge> edges = edgeMapper.selectList(
                new LambdaQueryWrapper<KgEdge>().eq(KgEdge::getCourseId, courseId).orderByAsc(KgEdge::getId));

        // 主键 -> 业务编码（边的端点回填为编码，供前端用）
        Map<Long, String> idToCode = new HashMap<>();
        for (KgNode n : nodes) {
            idToCode.put(n.getId(), n.getNodeCode());
        }

        // 有 studentId 时查掌握度，建 nodeId -> 掌握行
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
            if (sm != null) {
                BigDecimal score = sm.getMasteryScore();
                nv.setMastery(levelToMastery(sm.getMasteryLevel()));
                nv.setMasteryScore(score != null ? score.doubleValue() : null);
            } else {
                nv.setMastery(MASTERY_UNLEARNED);
                nv.setMasteryScore(null);
            }
            nodeVOs.add(nv);
        }
        vo.setNodes(nodeVOs);

        List<GraphDataVO.EdgeVO> edgeVOs = new ArrayList<>(edges.size());
        for (KgEdge e : edges) {
            GraphDataVO.EdgeVO ev = new GraphDataVO.EdgeVO();
            ev.setSource(idToCode.get(e.getFromNodeId()));
            ev.setTarget(idToCode.get(e.getToNodeId()));
            ev.setType(RelationType.labelOf(e.getRelationType()));
            edgeVOs.add(ev);
        }
        vo.setEdges(edgeVOs);

        return vo;
    }

    /** 掌握等级 TINYINT → 前端三态串：2=已掌握 / 1=薄弱 / 0 及空值或未知=未学。 */
    private String levelToMastery(Integer level) {
        if (level == null) {
            return MASTERY_UNLEARNED;
        }
        return level == 2 ? MASTERY_MASTERED : level == 1 ? MASTERY_WEAK : MASTERY_UNLEARNED;
    }
}
