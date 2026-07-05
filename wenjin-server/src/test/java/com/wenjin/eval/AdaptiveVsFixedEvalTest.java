package com.wenjin.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.AdaptiveAnswerRequest;
import com.wenjin.dto.AdaptiveDiagnosticVO;
import com.wenjin.dto.PaperQuestionVO;
import com.wenjin.dto.PaperVO;
import com.wenjin.dto.SubmitRequest;
import com.wenjin.entity.AdaptiveDiagSession;
import com.wenjin.entity.AnswerRecord;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.MasterySnapshot;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.entity.StudentCourse;
import com.wenjin.entity.StudentMastery;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.AdaptiveDiagSessionMapper;
import com.wenjin.mapper.AnswerRecordMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.MasterySnapshotMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.mapper.StudentCourseMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.service.AdaptiveDiagnosticService;
import com.wenjin.service.DiagnosticService;
import com.wenjin.service.MasteryService;
import com.wenjin.support.AdaptiveDiagState;
import com.wenjin.support.NodeState;
import com.wenjin.support.PrereqDirection;
import com.wenjin.support.QuestionType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 自适应 vs 固定卷对照评估脚本（M2 T9 答辩素材生成器）。
 *
 * <p><b>运行前提</b>：
 * <ol>
 *   <li>MySQL 本地服务已启动（root/root，数据库 wenjin）；</li>
 *   <li>演示库干净：课程 5（软件工程）存在且有 39 节点 / ≥127 道 APPROVED 题；</li>
 *   <li>无正在进行的自适应会话影响。</li>
 * </ol>
 *
 * <p><b>运行方式</b>：注释本类的 {@code @Disabled} 注解 →
 * {@code .\mvnw test -Dtest=AdaptiveVsFixedEvalTest -pl wenjin-server}
 * → 还原 {@code @Disabled} 再提交。
 *
 * <p><b>产出位置</b>：控制台输出 + {@code wenjin-server/target/adaptive-vs-fixed-eval.md}
 * （target/ 不入库）。
 *
 * <p><b>清理承诺</b>：finally 块删除所有 {@code eval_} 前缀学生的所有关联行，演示库不留渣。
 */
@SpringBootTest(properties = {"spring.sql.init.mode=never"})
@Disabled("手动评估：确认 MySQL 演示库可用后注释本行运行" +
          "\n运行: .\\mvnw test -Dtest=AdaptiveVsFixedEvalTest -pl wenjin-server" +
          "\n产出: console + wenjin-server/target/adaptive-vs-fixed-eval.md")
class AdaptiveVsFixedEvalTest {

    /** 评估目标课程（软件工程，演示库 courseId=5，39 节点，127 APPROVED 题）。 */
    private static final long COURSE_ID = 5L;
    /** 评估学生用户名前缀（方便清理）。 */
    private static final String EVAL_PREFIX = "eval_";
    /** 学生角色值。 */
    private static final int ROLE_STUDENT = 2;
    /** 学生账号主知识点权重=1。 */
    private static final int WEIGHT_MAIN = 1;

    @Autowired private AdaptiveDiagnosticService adaptiveDiagnosticService;
    @Autowired private DiagnosticService diagnosticService;
    @Autowired private MasteryService masteryService;

    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private StudentCourseMapper studentCourseMapper;
    @Autowired private StudentMasteryMapper studentMasteryMapper;
    @Autowired private KgNodeMapper kgNodeMapper;
    @Autowired private KgEdgeMapper kgEdgeMapper;
    @Autowired private QuestionNodeMapper questionNodeMapper;
    @Autowired private QuestionOptionMapper questionOptionMapper;
    @Autowired private AdaptiveDiagSessionMapper adaptiveDiagSessionMapper;
    @Autowired private AnswerRecordMapper answerRecordMapper;
    @Autowired private MasterySnapshotMapper masterySnapshotMapper;

    // ─────────────────────────────────────────────────────────────────────────────
    // 主评估入口
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("自适应 vs 固定卷对照评估（全弱/半弱/全强三画像）")
    void evaluate() throws IOException {
        List<Long> evalStudentIds = new ArrayList<>();
        try {
            // ── 1. 加载课程图谱 ────────────────────────────────────────────────
            List<KgNode> courseNodes = kgNodeMapper.selectList(
                    new LambdaQueryWrapper<KgNode>().eq(KgNode::getCourseId, COURSE_ID));
            List<KgEdge> prereqEdges = kgEdgeMapper.selectList(
                    new LambdaQueryWrapper<KgEdge>()
                            .eq(KgEdge::getCourseId, COURSE_ID)
                            .eq(KgEdge::getRelationType, PrereqDirection.RELATION_TYPE_PREREQUISITE));

            List<Long> allNodeIdList = courseNodes.stream()
                    .map(KgNode::getId).sorted().collect(Collectors.toList());
            Set<Long> allNodeIdSet = new HashSet<>(allNodeIdList);
            int totalNodes = allNodeIdList.size();

            System.out.printf("%n═══ M2 T9 自适应 vs 固定卷对照评估 ═══%n");
            System.out.printf("课程 ID=%d，节点数=%d，前置边数=%d%n%n",
                    COURSE_ID, totalNodes, prereqEdges.size());

            // ── 2. 计算半弱节点集（Kahn 分层后半）────────────────────────────
            Set<Long> halfWeakNodes = EvalSupport.kahnBackHalf(allNodeIdList, prereqEdges);
            System.out.printf("半弱弱点集大小：%d / %d 个节点%n%n", halfWeakNodes.size(), totalNodes);

            // ── 3. 三画像逐个评估 ──────────────────────────────────────────────
            long epochSeconds = Instant.now().getEpochSecond();
            List<String> profiles = List.of("全弱", "半弱", "全强");
            List<EvalSupport.ProfileResult> results = new ArrayList<>();

            for (String profile : profiles) {
                Set<Long> weakNodes = switch (profile) {
                    case "全弱" -> allNodeIdSet;
                    case "半弱" -> halfWeakNodes;
                    default     -> Set.of();
                };

                System.out.printf("─── 画像：%s（弱点节点=%d）───%n", profile, weakNodes.size());

                // 建评估学生（自适应臂 + 固定臂）
                String adaptiveName = EVAL_PREFIX + profile + "_adaptive_" + epochSeconds;
                String fixedName    = EVAL_PREFIX + profile + "_fixed_" + epochSeconds;

                Long adaptiveId = createEvalStudent(adaptiveName);
                Long fixedId    = createEvalStudent(fixedName);
                evalStudentIds.add(adaptiveId);
                evalStudentIds.add(fixedId);

                enrollInCourse(adaptiveId, COURSE_ID);
                enrollInCourse(fixedId, COURSE_ID);

                // 运行自适应臂
                int[] cannotConstruct = {0};
                AdaptiveDiagnosticVO finalVO = runAdaptiveArm(adaptiveId, weakNodes, cannotConstruct);
                System.out.printf("  自适应臂完成，sessionId=%d，cannotConstruct=%d%n",
                        finalVO.getSessionId(), cannotConstruct[0]);

                // 运行固定卷臂
                int[] fixedCannotConstruct = {0};
                int fixedPaperSize = runFixedArm(fixedId, weakNodes, fixedCannotConstruct);
                System.out.printf("  固定卷臂完成，paperSize=%d，cannotConstruct=%d%n",
                        fixedPaperSize, fixedCannotConstruct[0]);

                // 收集指标
                EvalSupport.ProfileResult pr = collectMetrics(
                        profile, finalVO, adaptiveId, fixedId,
                        allNodeIdList, allNodeIdSet, totalNodes, weakNodes);
                results.add(pr);
                System.out.printf("  着色一致率=%.1f%%，自适应准确率=%.1f%%，固定准确率=%.1f%%%n%n",
                        pr.agreementRate() * 100, pr.adaptiveAccuracy() * 100, pr.fixedAccuracy() * 100);
            }

            // ── 4. 输出 markdown 表与结论行 ────────────────────────────────────
            String table = EvalSupport.renderMarkdownTable(results);
            StringBuilder out = new StringBuilder();
            out.append("# M2 自适应 vs 固定卷对照评估\n\n");
            out.append("## 指标汇总表\n\n");
            out.append(table);
            out.append("\n");
            out.append("## 结论（答辩素材）\n\n");
            for (EvalSupport.ProfileResult r : results) {
                out.append(EvalSupport.conclusionLine(r)).append("\n\n");
            }
            out.append("---\n");
            out.append("*注：固定卷二分类阈值=50（65/30 冷启动中点）；");
            out.append("自适应 UNKNOWN 节点排除于准确率分母。*\n");

            System.out.println("═══ 评估结果 ═══");
            System.out.println(out);

            // 写文件
            Path reportPath = Paths.get("target", "adaptive-vs-fixed-eval.md");
            Files.createDirectories(reportPath.getParent());
            Files.write(reportPath, out.toString().getBytes(StandardCharsets.UTF_8));
            System.out.printf("已写入：%s%n%n", reportPath.toAbsolutePath());

            // ── 5. 验收锚点打印 ────────────────────────────────────────────────
            printAcceptanceCheck(results);

        } finally {
            cleanup(evalStudentIds);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 验收锚点
    // ─────────────────────────────────────────────────────────────────────────────

    private void printAcceptanceCheck(List<EvalSupport.ProfileResult> results) {
        System.out.println("═══ 验收锚点 ═══");
        for (EvalSupport.ProfileResult r : results) {
            if ("半弱".equals(r.profile())) {
                boolean v1 = r.adaptiveQuestionCount() <= 15
                        && r.unknownCount() == 0;
                System.out.printf("[验收1] 半弱 ≤15 题且全图分类：%s（题数=%d，UNKNOWN=%d）%n",
                        v1 ? "✓ PASS" : "✗ FAIL", r.adaptiveQuestionCount(), r.unknownCount());
                boolean v3 = r.agreementRate() >= 0.80;
                System.out.printf("[验收3] 着色一致率 ≥80%%：%s（实际=%.1f%%）%n",
                        v3 ? "✓ PASS" : "✗ FAIL (需分析，勿调口径)", r.agreementRate() * 100);
            }
        }
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 自适应臂
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 运行自适应臂：start → 循环 answer 直到 done。
     * 每轮从探针 questionIds 查库构造 oracle 作答（弱点节点提交错误答案，否则正确）。
     */
    private AdaptiveDiagnosticVO runAdaptiveArm(Long studentId, Set<Long> weakNodes,
                                                 int[] cannotConstructCounter) {
        // start（CurrentUser 设置是防御性的——service 层不读 ThreadLocal，但遵循简报约定）
        CurrentUser.set(studentId);
        AdaptiveDiagnosticVO vo;
        try {
            vo = adaptiveDiagnosticService.start(studentId, COURSE_ID);
        } finally {
            CurrentUser.clear();
        }

        while (!vo.isDone()) {
            AdaptiveDiagnosticVO.ProbeVO probe = vo.getProbe();
            Long probeNodeId = probe.getNodeId();
            boolean isWeak = weakNodes.contains(probeNodeId);

            List<Long> questionIds = probe.getQuestions().stream()
                    .map(PaperQuestionVO::getQuestionId).collect(Collectors.toList());

            // 查正确答案与全部选项（oracle 需要）
            Map<Long, Set<String>> correctKeys = loadCorrectKeys(questionIds);
            Map<Long, Set<String>> allKeys = loadAllKeys(questionIds);

            List<AdaptiveAnswerRequest.AnswerItem> items = new ArrayList<>();
            for (PaperQuestionVO q : probe.getQuestions()) {
                Long qid = q.getQuestionId();
                int type = q.getType() != null ? q.getType() : QuestionType.SINGLE;
                Set<String> cKeys = correctKeys.getOrDefault(qid, Set.of());
                Set<String> aKeys = allKeys.getOrDefault(qid, Set.of());

                String answer;
                if (!isWeak) {
                    answer = buildCorrectAnswer(type, cKeys);
                } else {
                    String wrong = buildWrongAnswer(type, cKeys, aKeys);
                    if (wrong == null) {
                        cannotConstructCounter[0]++;
                        answer = buildCorrectAnswer(type, cKeys);
                    } else {
                        answer = wrong;
                    }
                }

                AdaptiveAnswerRequest.AnswerItem item = new AdaptiveAnswerRequest.AnswerItem();
                item.setQuestionId(qid);
                item.setStudentAnswer(answer);
                items.add(item);
            }

            AdaptiveAnswerRequest req = new AdaptiveAnswerRequest();
            req.setStudentId(studentId);
            req.setAnswers(items);

            Long sessionId = vo.getSessionId();
            CurrentUser.set(studentId);
            try {
                vo = adaptiveDiagnosticService.answer(sessionId, req);
            } finally {
                CurrentUser.clear();
            }
        }
        return vo;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 固定卷臂
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 运行固定卷臂：composePaper → oracle 作答 → submit。
     *
     * @return 实际下发题数
     */
    private int runFixedArm(Long studentId, Set<Long> weakNodes, int[] cannotConstructCounter) {
        CurrentUser.set(studentId);
        PaperVO paper;
        try {
            paper = diagnosticService.composePaper(COURSE_ID);
        } finally {
            CurrentUser.clear();
        }

        if (paper.getQuestions().isEmpty()) {
            return 0;
        }

        List<Long> questionIds = paper.getQuestions().stream()
                .map(PaperQuestionVO::getQuestionId).collect(Collectors.toList());

        // 查每题主知识点（weight=1），决定是否是弱点题
        Map<Long, Long> primaryNodeByQuestion = loadPrimaryNodeByQuestion(questionIds);
        Map<Long, Set<String>> correctKeys = loadCorrectKeys(questionIds);
        Map<Long, Set<String>> allKeys = loadAllKeys(questionIds);

        List<SubmitRequest.Answer> answers = new ArrayList<>();
        for (PaperQuestionVO q : paper.getQuestions()) {
            Long qid = q.getQuestionId();
            int type = q.getType() != null ? q.getType() : QuestionType.SINGLE;
            Long nodeId = primaryNodeByQuestion.get(qid);
            boolean isWeak = nodeId != null && weakNodes.contains(nodeId);
            Set<String> cKeys = correctKeys.getOrDefault(qid, Set.of());
            Set<String> aKeys = allKeys.getOrDefault(qid, Set.of());

            String optionKey;
            if (!isWeak) {
                optionKey = buildCorrectAnswer(type, cKeys);
            } else {
                String wrong = buildWrongAnswer(type, cKeys, aKeys);
                if (wrong == null) {
                    cannotConstructCounter[0]++;
                    optionKey = buildCorrectAnswer(type, cKeys);
                } else {
                    optionKey = wrong;
                }
            }

            SubmitRequest.Answer ans = new SubmitRequest.Answer();
            ans.setQuestionId(qid);
            ans.setOptionKey(optionKey);
            answers.add(ans);
        }

        SubmitRequest req = new SubmitRequest();
        req.setStudentId(studentId);
        req.setCourseId(COURSE_ID);
        req.setAnswers(answers);

        CurrentUser.set(studentId);
        try {
            diagnosticService.submit(req);
        } finally {
            CurrentUser.clear();
        }

        return paper.getQuestions().size();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 指标收集
    // ─────────────────────────────────────────────────────────────────────────────

    private EvalSupport.ProfileResult collectMetrics(
            String profile,
            AdaptiveDiagnosticVO finalAdaptiveVO,
            Long adaptiveStudentId,
            Long fixedStudentId,
            List<Long> allNodeIdList,
            Set<Long> allNodeIdSet,
            int totalNodes,
            Set<Long> weakNodes) {

        // ── 自适应臂：读最近一次已完成会话的 state_json ──────────────────────
        AdaptiveDiagSession adaptiveSession = adaptiveDiagSessionMapper.selectOne(
                new QueryWrapper<AdaptiveDiagSession>()
                        .eq("student_id", adaptiveStudentId)
                        .eq("course_id", COURSE_ID)
                        .eq("status", 1) // SESSION_DONE
                        .orderByDesc("finished_at")
                        .orderByDesc("id")
                        .last("LIMIT 1"));

        // nodeStates 包含全部节点（AdaptiveEngine 初始化时给所有节点设 UNKNOWN）
        Map<Long, NodeState> adaptiveNodeStates = new HashMap<>();
        int budgetUsed = 0;
        if (adaptiveSession != null) {
            AdaptiveDiagState state = AdaptiveDiagState.fromJson(adaptiveSession.getStateJson());
            adaptiveNodeStates.putAll(state.nodeStates());
            budgetUsed = state.budgetUsed();
        }
        // 未在 nodeStates 中的节点兜底 UNKNOWN（防御性）
        for (Long nodeId : allNodeIdList) {
            adaptiveNodeStates.putIfAbsent(nodeId, NodeState.UNKNOWN);
        }

        // 自适应掌握度 level（从 student_mastery 表读）
        Map<Long, Integer> adaptiveLevels = loadMasteryLevels(adaptiveStudentId);

        // ── 固定卷臂：读 student_mastery ──────────────────────────────────────
        List<StudentMastery> fixedMasteryRows = studentMasteryMapper.selectList(
                new LambdaQueryWrapper<StudentMastery>()
                        .eq(StudentMastery::getStudentId, fixedStudentId)
                        .eq(StudentMastery::getCourseId, COURSE_ID));
        Map<Long, Integer> fixedLevels = new HashMap<>();
        Map<Long, Double> fixedScores = new HashMap<>();
        for (StudentMastery sm : fixedMasteryRows) {
            fixedLevels.put(sm.getNodeId(), sm.getMasteryLevel());
            if (sm.getMasteryScore() != null) {
                fixedScores.put(sm.getNodeId(), sm.getMasteryScore().doubleValue());
            }
        }

        // ── 覆盖率 ────────────────────────────────────────────────────────────
        int adaptiveCovered = (int) adaptiveNodeStates.values().stream()
                .filter(s -> s != NodeState.UNKNOWN).count();
        int unknownCount = (int) adaptiveNodeStates.values().stream()
                .filter(s -> s == NodeState.UNKNOWN).count();
        int fixedCovered = fixedLevels.size();

        // ── 着色一致率 ────────────────────────────────────────────────────────
        double agreementRate = EvalSupport.computeAgreementRate(
                adaptiveLevels, fixedLevels, allNodeIdSet);
        double intersectionRate = EvalSupport.computeIntersectionAgreementRate(
                adaptiveLevels, fixedLevels);

        // ── 二分类准确率 ──────────────────────────────────────────────────────
        // 自适应：state-based 二分类
        Map<Long, Boolean> adaptivePreds = new HashMap<>();
        for (Long nodeId : allNodeIdList) {
            adaptivePreds.put(nodeId,
                    EvalSupport.binaryClassifyByState(adaptiveNodeStates.get(nodeId)));
        }
        double adaptiveAccuracy = EvalSupport.computeAccuracy(
                adaptivePreds, weakNodes, allNodeIdSet);

        // 固定卷：score-based 二分类（无行 = 0分 < 50 → FAIL=false）
        Map<Long, Boolean> fixedPreds = new HashMap<>();
        for (Long nodeId : allNodeIdList) {
            double score = fixedScores.getOrDefault(nodeId, 0.0);
            fixedPreds.put(nodeId, EvalSupport.binaryClassifyByScore(score));
        }
        double fixedAccuracy = EvalSupport.computeAccuracy(
                fixedPreds, weakNodes, allNodeIdSet);

        return new EvalSupport.ProfileResult(
                profile, budgetUsed, 25 /* fixed 标称 25 题 */,
                adaptiveCovered, fixedCovered, totalNodes, unknownCount,
                agreementRate, intersectionRate, adaptiveAccuracy, fixedAccuracy);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 建数辅助
    // ─────────────────────────────────────────────────────────────────────────────

    private Long createEvalStudent(String username) {
        SysUser user = new SysUser();
        user.setUsername(username);
        // 明文密码占位（评估完即删，无需 BCrypt）
        user.setPassword("$2a$10$eval_placeholder_password_hash");
        user.setRealName("EvalStudent-" + username);
        user.setRole(ROLE_STUDENT);
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        sysUserMapper.insert(user);
        return user.getId();
    }

    private void enrollInCourse(Long studentId, long courseId) {
        StudentCourse sc = new StudentCourse();
        sc.setStudentId(studentId);
        sc.setCourseId(courseId);
        sc.setCreatedAt(LocalDateTime.now());
        studentCourseMapper.insert(sc);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 掌握度查询
    // ─────────────────────────────────────────────────────────────────────────────

    private Map<Long, Integer> loadMasteryLevels(Long studentId) {
        List<StudentMastery> rows = studentMasteryMapper.selectList(
                new LambdaQueryWrapper<StudentMastery>()
                        .eq(StudentMastery::getStudentId, studentId)
                        .eq(StudentMastery::getCourseId, COURSE_ID));
        Map<Long, Integer> map = new HashMap<>();
        for (StudentMastery sm : rows) {
            map.put(sm.getNodeId(), sm.getMasteryLevel());
        }
        return map;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 正确答案查询（oracle 用）
    // ─────────────────────────────────────────────────────────────────────────────

    /** questionId → is_correct=1 的 option key 集合。 */
    private Map<Long, Set<String>> loadCorrectKeys(List<Long> questionIds) {
        if (questionIds.isEmpty()) return Map.of();
        List<QuestionOption> opts = questionOptionMapper.selectList(
                new LambdaQueryWrapper<QuestionOption>()
                        .in(QuestionOption::getQuestionId, questionIds)
                        .eq(QuestionOption::getIsCorrect, 1));
        Map<Long, Set<String>> map = new HashMap<>();
        for (QuestionOption o : opts) {
            map.computeIfAbsent(o.getQuestionId(), k -> new HashSet<>()).add(o.getOptionKey());
        }
        return map;
    }

    /** questionId → 全部 option key 集合（用于构造错误答案）。 */
    private Map<Long, Set<String>> loadAllKeys(List<Long> questionIds) {
        if (questionIds.isEmpty()) return Map.of();
        List<QuestionOption> opts = questionOptionMapper.selectList(
                new LambdaQueryWrapper<QuestionOption>()
                        .in(QuestionOption::getQuestionId, questionIds));
        Map<Long, Set<String>> map = new HashMap<>();
        for (QuestionOption o : opts) {
            map.computeIfAbsent(o.getQuestionId(), k -> new HashSet<>()).add(o.getOptionKey());
        }
        return map;
    }

    /** questionId → 主知识点 nodeId（weight=1 第一条）。 */
    private Map<Long, Long> loadPrimaryNodeByQuestion(List<Long> questionIds) {
        if (questionIds.isEmpty()) return Map.of();
        List<QuestionNode> links = questionNodeMapper.selectList(
                new LambdaQueryWrapper<QuestionNode>()
                        .in(QuestionNode::getQuestionId, questionIds)
                        .eq(QuestionNode::getWeight, WEIGHT_MAIN));
        Map<Long, Long> map = new HashMap<>();
        for (QuestionNode qn : links) {
            map.putIfAbsent(qn.getQuestionId(), qn.getNodeId());
        }
        return map;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Oracle 作答构造
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 构造正确答案字符串：
     * <ul>
     *   <li>单选/判断：字典序最小的正确 key</li>
     *   <li>多选：全部正确 key 排序后逗号连接</li>
     *   <li>空 correctKeys（题库异常）：返回 "A" 防御</li>
     * </ul>
     */
    private String buildCorrectAnswer(int type, Set<String> correctKeys) {
        if (correctKeys.isEmpty()) return "A";
        TreeSet<String> sorted = new TreeSet<>(correctKeys);
        if (type == QuestionType.MULTI) {
            return String.join(",", sorted);
        }
        return sorted.first();
    }

    /**
     * 构造确定性错误答案（用于弱点节点）。
     *
     * <ul>
     *   <li>单选/判断：字典序最小的"非正确"key；无则返回 null（无法构造）</li>
     *   <li>多选："正确集去一加一"——从 correctKeys 中移除字典序最大的 key，
     *       再加入字典序最小的"非正确"key；若无错误 key 可加，则直接提交 (correctKeys-1) 子集；
     *       若子集等于 correctKeys（只有一个正确且题库仅一个选项）则返回 null。</li>
     * </ul>
     *
     * @return 错误答案字符串；无法构造时返回 {@code null}（调用方应改提交正确答案并计数）
     */
    private String buildWrongAnswer(int type, Set<String> correctKeys, Set<String> allKeys) {
        if (correctKeys.isEmpty()) return null;

        TreeSet<String> wrongKeys = new TreeSet<>(allKeys);
        wrongKeys.removeAll(correctKeys);

        if (type == QuestionType.MULTI) {
            TreeSet<String> base = new TreeSet<>(correctKeys);
            // 移除字典序最大（last）的正确 key
            base.remove(base.last());
            if (!wrongKeys.isEmpty()) {
                // 加入字典序最小的错误 key
                base.add(wrongKeys.first());
            }
            // 校验结果不等于原正确集
            if (base.equals(correctKeys)) return null;
            if (base.isEmpty()) return null;
            return String.join(",", base);
        } else {
            // 单选 / 判断
            if (wrongKeys.isEmpty()) return null;
            return wrongKeys.first();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 清理（finally 保证，演示库不留渣）
    // ─────────────────────────────────────────────────────────────────────────────

    private void cleanup(List<Long> evalStudentIds) {
        if (evalStudentIds == null || evalStudentIds.isEmpty()) return;
        System.out.printf("━━━ 清理评估学生（%d 个）及关联数据 ━━━%n", evalStudentIds.size());
        try {
            // adaptive_diag_session
            adaptiveDiagSessionMapper.delete(new LambdaQueryWrapper<AdaptiveDiagSession>()
                    .in(AdaptiveDiagSession::getStudentId, evalStudentIds));
            // answer_record
            answerRecordMapper.delete(new LambdaQueryWrapper<AnswerRecord>()
                    .in(AnswerRecord::getStudentId, evalStudentIds));
            // mastery_snapshot
            masterySnapshotMapper.delete(new LambdaQueryWrapper<MasterySnapshot>()
                    .in(MasterySnapshot::getStudentId, evalStudentIds));
            // student_mastery
            studentMasteryMapper.delete(new LambdaQueryWrapper<StudentMastery>()
                    .in(StudentMastery::getStudentId, evalStudentIds));
            // student_course
            studentCourseMapper.delete(new LambdaQueryWrapper<StudentCourse>()
                    .in(StudentCourse::getStudentId, evalStudentIds));
            // sys_user
            sysUserMapper.deleteBatchIds(evalStudentIds);
            System.out.println("清理完成，演示库无 eval_ 学生残留。");
        } catch (Exception e) {
            System.err.println("[WARN] 清理过程中出现异常（请手动检查 eval_ 学生）：" + e.getMessage());
        }
    }
}
