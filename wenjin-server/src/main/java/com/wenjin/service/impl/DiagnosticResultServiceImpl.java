package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.dto.DiagnosticResultVO;
import com.wenjin.dto.DiagnosticResultVO.BasisItem;
import com.wenjin.dto.DiagnosticResultVO.ChainNode;
import com.wenjin.dto.DiagnosticResultVO.Coverage;
import com.wenjin.dto.DiagnosticResultVO.Distribution;
import com.wenjin.dto.DiagnosticResultVO.NodeRef;
import com.wenjin.dto.DiagnosticResultVO.PendingNode;
import com.wenjin.dto.DiagnosticResultVO.RootCause;
import com.wenjin.entity.AnswerRecord;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.AnswerRecordMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.DiagnosticResultService;
import com.wenjin.support.QuestionStatus;
import com.wenjin.support.RelationType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 诊断回溯服务实现（PRD 6.2）。
 * <p>卡点 = 最下游薄弱点（有薄弱前置的薄弱节点中分值最低）；沿前置边逆向 ≤2 跳，
 * 每跳按 距离权重(1跳1.0/2跳0.5)×缺口(75−分) 选续链节点，最上游薄弱点 = 根因。
 * 无作答数据的前置标"待验证"并推验证题；前置全已掌握则根因=本节点。
 */
@Service
public class DiagnosticResultServiceImpl implements DiagnosticResultService {

    private static final int PREREQ = RelationType.PREREQUISITE.getCode();
    private static final int MAX_HOPS = 2;
    private static final int MAX_SUSPECTS = 3;
    private static final int MAX_VERIFY_Q = 5;
    private static final String[] ORDINALS = {"一", "二", "三", "四"};

    private final StudentMasteryMapper studentMasteryMapper;
    private final KgNodeMapper kgNodeMapper;
    private final KgEdgeMapper kgEdgeMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final AnswerRecordMapper answerRecordMapper;
    private final QuestionMapper questionMapper;

    @Value("${wenjin.mastery.mastered-threshold:75}")
    private double masteredThreshold;

    public DiagnosticResultServiceImpl(StudentMasteryMapper studentMasteryMapper,
                                       KgNodeMapper kgNodeMapper,
                                       KgEdgeMapper kgEdgeMapper,
                                       QuestionNodeMapper questionNodeMapper,
                                       QuestionOptionMapper questionOptionMapper,
                                       AnswerRecordMapper answerRecordMapper,
                                       QuestionMapper questionMapper) {
        this.studentMasteryMapper = studentMasteryMapper;
        this.kgNodeMapper = kgNodeMapper;
        this.kgEdgeMapper = kgEdgeMapper;
        this.questionNodeMapper = questionNodeMapper;
        this.questionOptionMapper = questionOptionMapper;
        this.answerRecordMapper = answerRecordMapper;
        this.questionMapper = questionMapper;
    }

    /** 内部：嫌疑（节点 + 加权分=距离权重×缺口）。 */
    private record Suspect(Long nodeId, double weighted) {}

    @Override
    public DiagnosticResultVO getResult(Long studentId, Long courseId) {
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

        List<KgEdge> edges = kgEdgeMapper.selectList(
                new LambdaQueryWrapper<KgEdge>()
                        .eq(KgEdge::getCourseId, courseId)
                        .eq(KgEdge::getRelationType, PREREQ));
        Map<Long, List<Long>> prereqMap = new HashMap<>(); // toNodeId -> [fromNodeId]
        for (KgEdge e : edges) {
            prereqMap.computeIfAbsent(e.getToNodeId(), k -> new ArrayList<>()).add(e.getFromNodeId());
        }

        DiagnosticResultVO vo = new DiagnosticResultVO();
        vo.setDistribution(buildDistribution(nodes, mById));
        Coverage cov = new Coverage();
        cov.setCovered(rows.size());
        cov.setTotal(nodes.size());
        vo.setCoverage(cov);
        vo.setQuestionsAnswered(Math.toIntExact(answerRecordMapper.selectCount(
                new LambdaQueryWrapper<AnswerRecord>()
                        .eq(AnswerRecord::getStudentId, studentId)
                        .eq(AnswerRecord::getCourseId, courseId))));

        // 薄弱集：有行、节点存在、且 score<阈值
        List<Long> weak = new ArrayList<>();
        for (StudentMastery m : rows) {
            if (nodeById.containsKey(m.getNodeId())
                    && m.getMasteryScore() != null && m.getMasteryScore().doubleValue() < masteredThreshold) {
                weak.add(m.getNodeId());
            }
        }
        if (weak.isEmpty()) {
            vo.setHasWeakness(false);
            vo.setChain(List.of());
            vo.setBases(List.of());
            vo.setSuspects(List.of());
            vo.setPendingVerification(List.of());
            return vo;
        }
        Set<Long> weakSet = new LinkedHashSet<>(weak);

        Comparator<Long> byScoreThenKey = Comparator
                .comparingDouble((Long id) -> scoreOf(mById, id))
                .thenComparingInt(id -> isKey(nodeById, id) ? 0 : 1)
                .thenComparingLong(id -> id);

        // 卡点：有薄弱前置的薄弱点中最低分；无则薄弱集最低分（根因=自身）
        List<Long> candidates = new ArrayList<>();
        for (Long w : weak) {
            boolean hasWeakPrereq = prereqMap.getOrDefault(w, List.of()).stream().anyMatch(weakSet::contains);
            if (hasWeakPrereq) {
                candidates.add(w);
            }
        }
        Long stuckId;
        if (!candidates.isEmpty()) {
            candidates.sort(byScoreThenKey);
            stuckId = candidates.get(0);
        } else {
            List<Long> ws = new ArrayList<>(weak);
            ws.sort(byScoreThenKey);
            stuckId = ws.get(0);
        }

        vo.setHasWeakness(true);
        vo.setStuckNode(nodeRef(nodeById, mById, stuckId));

        // 回溯
        LinkedList<Long> chainIds = new LinkedList<>();
        chainIds.addFirst(stuckId);
        Long cur = stuckId;
        int hops = 0;
        List<Suspect> suspects = new ArrayList<>();
        LinkedHashSet<Long> pending = new LinkedHashSet<>();
        Long boundaryMastered = null;
        while (hops < MAX_HOPS) {
            double w = (hops == 0) ? 1.0 : 0.5;
            List<Long> weakPre = new ArrayList<>();
            for (Long p : prereqMap.getOrDefault(cur, List.of())) {
                if (!nodeById.containsKey(p)) {
                    continue;  // 跳过孤儿前置边（端点不在本课程节点集，防御）
                }
                StudentMastery row = mById.get(p);
                if (row == null || row.getMasteryScore() == null) {
                    pending.add(p);
                    continue;
                }
                double s = row.getMasteryScore().doubleValue();
                if (s >= masteredThreshold) {
                    if (boundaryMastered == null || s > scoreOf(mById, boundaryMastered)) {
                        boundaryMastered = p;  // 取分值最高的已掌握上游边界
                    }
                } else {
                    weakPre.add(p);
                    suspects.add(new Suspect(p, w * (masteredThreshold - s)));
                }
            }
            if (weakPre.isEmpty()) {
                break;
            }
            weakPre.sort(Comparator
                    .comparingDouble((Long p) -> masteredThreshold - scoreOf(mById, p)).reversed()
                    .thenComparingLong(p -> p));
            cur = weakPre.get(0);
            chainIds.addFirst(cur);
            hops++;
        }
        Long rootId = chainIds.getFirst();

        // 链 VO
        List<ChainNode> chain = new ArrayList<>();
        int size = chainIds.size();
        for (int i = 0; i < size; i++) {
            Long id = chainIds.get(i);
            KgNode n = nodeById.get(id);
            ChainNode cn = new ChainNode();
            cn.setNodeCode(n.getNodeCode());
            cn.setName(n.getName());
            cn.setMasteryScore(scoreOrNull(mById, id));
            cn.setMasteryLevel(levelOrNull(mById, id));
            cn.setRole(size == 1 ? "stuck" : i == 0 ? "root" : i == size - 1 ? "stuck" : "middle");
            chain.add(cn);
        }
        vo.setChain(chain);

        // 根因
        KgNode rootNode = nodeById.get(rootId);
        RootCause rc = new RootCause();
        rc.setNodeCode(rootNode.getNodeCode());
        rc.setName(rootNode.getName());
        rc.setMasteryScore(scoreOrNull(mById, rootId));
        rc.setMasteryLevel(levelOrNull(mById, rootId));
        rc.setSelf(rootId.equals(stuckId));
        vo.setRootCause(rc);

        // 结论文案
        KgNode stuckNode = nodeById.get(stuckId);
        if (rc.isSelf()) {
            vo.setConclusionText("前置知识点均已掌握，问题就出在「" + stuckNode.getName()
                    + "」本身，针对性突破即可。");
        } else {
            vo.setConclusionText("根本原因更可能是前置点「" + rootNode.getName()
                    + "」掌握薄弱，而不是这个知识点本身——" + rootNode.getName()
                    + "不稳，「" + stuckNode.getName() + "」自然难以下手。");
        }

        // 嫌疑（去重、按加权降序、≤3）
        List<NodeRef> suspectRefs = new ArrayList<>();
        suspects.stream()
                .sorted(Comparator.comparingDouble(Suspect::weighted).reversed()
                        .thenComparingLong(Suspect::nodeId))
                .map(Suspect::nodeId).distinct().limit(MAX_SUSPECTS)
                .forEach(id -> suspectRefs.add(nodeRef(nodeById, mById, id)));
        vo.setSuspects(suspectRefs);

        // 依据
        vo.setBases(buildBases(studentId, courseId, stuckId, rootId, boundaryMastered,
                rc.isSelf(), nodeById, mById, chainIds));

        // 待验证 + 推题
        List<PendingNode> pendings = new ArrayList<>();
        for (Long p : pending) {
            KgNode n = nodeById.get(p);
            PendingNode pn = new PendingNode();
            pn.setNodeCode(n.getNodeCode());
            pn.setName(n.getName());
            pn.setSuggestedQuestionIds(suggestQuestions(courseId, p));
            pendings.add(pn);
        }
        vo.setPendingVerification(pendings);

        return vo;
    }

    // ── 依据 ───────────────────────────────────────────────
    private List<BasisItem> buildBases(Long studentId, Long courseId, Long stuckId, Long rootId,
                                       Long boundaryMastered, boolean self,
                                       Map<Long, KgNode> nodeById, Map<Long, StudentMastery> mById,
                                       LinkedList<Long> chainIds) {
        List<BasisItem> bases = new ArrayList<>();

        // 依据①：卡点错题 + distractor 证据
        List<QuestionNode> stuckQn = questionNodeMapper.selectList(
                new LambdaQueryWrapper<QuestionNode>().eq(QuestionNode::getNodeId, stuckId));
        List<Long> stuckQids = stuckQn.stream().map(QuestionNode::getQuestionId).distinct().toList();
        int answered = 0;
        int wrong = 0;
        String distractorHint = "";
        if (!stuckQids.isEmpty()) {
            List<AnswerRecord> recs = answerRecordMapper.selectList(
                    new LambdaQueryWrapper<AnswerRecord>()
                            .eq(AnswerRecord::getStudentId, studentId)
                            .eq(AnswerRecord::getCourseId, courseId)
                            .in(AnswerRecord::getQuestionId, stuckQids));
            answered = recs.size();
            // 错选项 (qid,key) -> pointNodeCode
            List<QuestionOption> opts = questionOptionMapper.selectList(
                    new LambdaQueryWrapper<QuestionOption>().in(QuestionOption::getQuestionId, stuckQids));
            Map<String, String> pncByQk = new HashMap<>();
            for (QuestionOption o : opts) {
                if (o.getPointNodeCode() != null) {
                    pncByQk.put(o.getQuestionId() + "#" + o.getOptionKey(), o.getPointNodeCode());
                }
            }
            Map<String, Integer> pncTally = new LinkedHashMap<>();
            for (AnswerRecord r : recs) {
                if (r.getIsCorrect() != null && r.getIsCorrect() == 0) {
                    wrong++;
                    String pnc = pncByQk.get(r.getQuestionId() + "#" + r.getStudentAnswer());
                    if (pnc != null) {
                        pncTally.merge(pnc, 1, Integer::sum);
                    }
                }
            }
            // 命中回溯链上某前置点的 distractor → 提示
            Set<String> chainCodes = new LinkedHashSet<>();
            for (Long id : chainIds) {
                chainCodes.add(nodeById.get(id).getNodeCode());
            }
            String top = pncTally.entrySet().stream()
                    .filter(en -> chainCodes.contains(en.getKey()))
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            if (top != null) {
                KgNode tn = nodeByCode(nodeById, top);
                if (tn != null) {
                    distractorHint = "，错误多指向「" + tn.getName() + "」";
                }
            }
        }
        KgNode stuck = nodeById.get(stuckId);
        BasisItem b1 = new BasisItem();
        b1.setText("「" + stuck.getName() + "」共作答 " + answered + " 题，答错 " + wrong + " 题" + distractorHint);
        b1.setSub("指向概念理解而非单纯练习量");
        b1.setScore(scoreOrNull(mById, stuckId));
        b1.setLevel(levelOrNull(mById, stuckId));
        bases.add(b1);

        // 依据②：根因前置（非 self）
        if (!self) {
            KgNode root = nodeById.get(rootId);
            BasisItem b2 = new BasisItem();
            b2.setText("前置点「" + root.getName() + "」测得掌握度 " + pct(scoreOrNull(mById, rootId)));
            b2.setSub("正是「" + stuck.getName() + "」的直接基础");
            b2.setScore(scoreOrNull(mById, rootId));
            b2.setLevel(levelOrNull(mById, rootId));
            bases.add(b2);
        }

        // 依据③：已掌握的上游边界
        if (boundaryMastered != null) {
            KgNode bn = nodeById.get(boundaryMastered);
            KgNode root = nodeById.get(rootId);
            BasisItem b3 = new BasisItem();
            b3.setText("更上游的「" + bn.getName() + "」已掌握（" + pct(scoreOrNull(mById, boundaryMastered))
                    + "），回溯到「" + root.getName() + "」为止");
            b3.setSub("说明问题不在更上游");
            b3.setScore(scoreOrNull(mById, boundaryMastered));
            b3.setLevel(levelOrNull(mById, boundaryMastered));
            bases.add(b3);
        }

        for (int i = 0; i < bases.size(); i++) {
            bases.get(i).setOrder(i < ORDINALS.length ? ORDINALS[i] : String.valueOf(i + 1));
        }
        return bases;
    }

    private List<Long> suggestQuestions(Long courseId, Long nodeId) {
        List<QuestionNode> qns = questionNodeMapper.selectList(
                new LambdaQueryWrapper<QuestionNode>().eq(QuestionNode::getNodeId, nodeId));
        List<Long> qids = qns.stream().map(QuestionNode::getQuestionId).distinct().toList();
        if (qids.isEmpty()) {
            return List.of();
        }
        List<Question> approved = questionMapper.selectList(
                new LambdaQueryWrapper<Question>()
                        .eq(Question::getCourseId, courseId)
                        .eq(Question::getStatus, QuestionStatus.APPROVED)
                        .in(Question::getId, qids)
                        .orderByAsc(Question::getId));
        return approved.stream().map(Question::getId).limit(MAX_VERIFY_Q).toList();
    }

    private Distribution buildDistribution(List<KgNode> nodes, Map<Long, StudentMastery> mById) {
        Distribution d = new Distribution();
        for (KgNode n : nodes) {
            StudentMastery m = mById.get(n.getId());
            int level = (m == null || m.getMasteryLevel() == null) ? 0 : m.getMasteryLevel();
            if (level == 2) {
                d.setMastered(d.getMastered() + 1);
            } else if (level == 1) {
                d.setWeak(d.getWeak() + 1);
            } else {
                d.setUnlearned(d.getUnlearned() + 1);
            }
        }
        return d;
    }

    // ── 工具 ───────────────────────────────────────────────
    private double scoreOf(Map<Long, StudentMastery> m, Long id) {
        StudentMastery r = m.get(id);
        return (r == null || r.getMasteryScore() == null) ? 0.0 : r.getMasteryScore().doubleValue();
    }

    private Double scoreOrNull(Map<Long, StudentMastery> m, Long id) {
        StudentMastery r = m.get(id);
        return (r == null || r.getMasteryScore() == null) ? null : r.getMasteryScore().doubleValue();
    }

    private Integer levelOrNull(Map<Long, StudentMastery> m, Long id) {
        StudentMastery r = m.get(id);
        return r == null ? null : r.getMasteryLevel();
    }

    private boolean isKey(Map<Long, KgNode> nodeById, Long id) {
        KgNode n = nodeById.get(id);
        return n != null && n.getIsKey() != null && n.getIsKey() == 1;
    }

    private NodeRef nodeRef(Map<Long, KgNode> nodeById, Map<Long, StudentMastery> mById, Long id) {
        KgNode n = nodeById.get(id);
        NodeRef ref = new NodeRef();
        ref.setNodeCode(n.getNodeCode());
        ref.setName(n.getName());
        ref.setChapter(n.getChapter());
        ref.setMasteryScore(scoreOrNull(mById, id));
        ref.setMasteryLevel(levelOrNull(mById, id));
        return ref;
    }

    private KgNode nodeByCode(Map<Long, KgNode> nodeById, String code) {
        for (KgNode n : nodeById.values()) {
            if (n.getNodeCode().equals(code)) {
                return n;
            }
        }
        return null;
    }

    private String pct(Double score) {
        return score == null ? "暂无" : Math.round(score) + "%";
    }
}
