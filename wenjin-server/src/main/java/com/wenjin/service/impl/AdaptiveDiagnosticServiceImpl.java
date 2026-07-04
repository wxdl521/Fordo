package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.AdaptiveAnswerRequest;
import com.wenjin.dto.AdaptiveDiagnosticVO;
import com.wenjin.dto.GradedAnswer;
import com.wenjin.dto.PaperQuestionVO;
import com.wenjin.entity.AdaptiveDiagSession;
import com.wenjin.entity.AnswerRecord;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.AdaptiveDiagSessionMapper;
import com.wenjin.mapper.AnswerRecordMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.AdaptiveDiagnosticService;
import com.wenjin.service.MasteryService;
import com.wenjin.support.AdaptiveDiagState;
import com.wenjin.support.AdaptiveEngine;
import com.wenjin.support.AnswerGrader;
import com.wenjin.support.NodeState;
import com.wenjin.support.PrereqDirection;
import com.wenjin.support.QuestionPicker;
import com.wenjin.support.QuestionStatus;
import com.wenjin.support.QuestionType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 图引导自适应诊断服务实现（M2 T4）。
 *
 * <h3>状态无内存化</h3>
 * <p>会话状态唯一事实源是 {@code adaptive_diag_session.state_json}（{@link AdaptiveDiagState}）。
 * 每次 HTTP 调用：加载图（一次查 kg_node + 一次查 kg_edge relation_type=1，内存邻接表）→
 * 反序列化状态 → 用六参构造器重建 {@link AdaptiveEngine}（恢复状态、不重放传播）→ 处理 →
 * 序列化写回。循环内绝不查库。
 *
 * <h3>探针（每节点 2 题）</h3>
 * <p>复用 {@link QuestionPicker}（M1 组卷策略），只出客观题（简报裁决 2 钉死"简答不作探针"）。
 * 2 题全对=TESTED_PASS，否则 TESTED_FAIL；题库不足 2 题时 1 题也可（1 对=PASS）。
 * 判分复用 {@link AnswerGrader}；实测走既有 {@link MasteryService#applyAnswers}（与固定卷同构）；
 * 作答明细写 {@code answer_record}（scene=1 诊断，session_id 指向本表）。
 *
 * <h3>引擎调用纪律（T2 评审遗留）</h3>
 * <p>只对 {@link AdaptiveEngine#selectNextNode()} 返回的节点发探针；
 * 记账（recordProbeResult）仅对当前在途探针一次；answer 先校验 status=0。
 *
 * <h3>与 T5 的接缝</h3>
 * <p>{@link #finalizeSession} 为最小桩：置 status=1 + finished_at、summary 返回四类计数 +
 * 空节点明细。T5 将在同一方法落库推断保守初值（INFERRED_PASS=70/INFERRED_FAIL=35，仅无既有行时）
 * + 快照 + 填充节点明细。resultUrl 先固定，T8 增强。
 */
@Service
public class AdaptiveDiagnosticServiceImpl implements AdaptiveDiagnosticService {

    /** 会话状态：进行中。 */
    private static final int SESSION_IN_PROGRESS = 0;
    /** 会话状态：已完成。 */
    private static final int SESSION_DONE = 1;
    /** 会话状态：已放弃。 */
    private static final int SESSION_ABANDONED = 2;

    /**
     * answer_record.scene：诊断（与固定 25 题诊断同口径）。
     * <p>注：session_id 按 scene 语义指向不同会话表——scene=2→practice_session，
     * scene=1 且 session_id 非空→adaptive_diag_session。
     */
    private static final int SCENE_DIAGNOSTIC = 1;
    private static final int IS_CORRECT = 1;
    private static final int IS_WRONG = 0;

    /** 每探针题数（简报钉死=2；题库不足 1 题也可，由组卷组件兜底）。 */
    private static final int PROBE_QUESTION_COUNT = 2;

    /**
     * 推断保守初值：INFERRED_PASS 节点写入 70（低于实测答对冷启动区间上界，留"实测更可信"余量）。
     * <p>M2 §2.2 裁决，不做 EWMA，直接常量写入，不违反"绝不另写掌握度计算"红线。
     */
    private static final int INFERRED_PASS_SCORE = 70;

    /**
     * 推断保守初值：INFERRED_FAIL 节点写入 35。
     */
    private static final int INFERRED_FAIL_SCORE = 35;

    private final KgNodeMapper kgNodeMapper;
    private final KgEdgeMapper kgEdgeMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final AnswerRecordMapper answerRecordMapper;
    private final AdaptiveDiagSessionMapper adaptiveDiagSessionMapper;
    private final MasteryService masteryService;
    private final StudentMasteryMapper studentMasteryMapper;

    /** 组卷抽取共享组件（M1 组卷策略；探针传"仅客观题"谓词）。 */
    private final QuestionPicker questionPicker;

    /** 题量预算（配置项 wenjin.diagnostic.adaptive.budget）。 */
    @Value("${wenjin.diagnostic.adaptive.budget:15}")
    private int budget;

    /**
     * 组卷近期排除窗口（天）。跨域复用 M1 练习的 {@code wenjin.practice.recency-days}——
     * 诊断探针与练习共享"同学生近期已答不重复出现"的语义，故共用同一配置项。
     */
    @Value("${wenjin.practice.recency-days:7}")
    private int recencyDays;

    public AdaptiveDiagnosticServiceImpl(KgNodeMapper kgNodeMapper,
                                         KgEdgeMapper kgEdgeMapper,
                                         QuestionNodeMapper questionNodeMapper,
                                         QuestionMapper questionMapper,
                                         QuestionOptionMapper questionOptionMapper,
                                         AnswerRecordMapper answerRecordMapper,
                                         AdaptiveDiagSessionMapper adaptiveDiagSessionMapper,
                                         MasteryService masteryService,
                                         StudentMasteryMapper studentMasteryMapper) {
        this.kgNodeMapper = kgNodeMapper;
        this.kgEdgeMapper = kgEdgeMapper;
        this.questionNodeMapper = questionNodeMapper;
        this.questionMapper = questionMapper;
        this.questionOptionMapper = questionOptionMapper;
        this.answerRecordMapper = answerRecordMapper;
        this.adaptiveDiagSessionMapper = adaptiveDiagSessionMapper;
        this.masteryService = masteryService;
        this.studentMasteryMapper = studentMasteryMapper;
        this.questionPicker = new QuestionPicker(questionNodeMapper, questionMapper);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // start
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdaptiveDiagnosticVO start(Long studentId, Long courseId) {
        // 鉴权 assertSelf / assertAccessibleByStudent 由 controller（T6）承担；本层专注建会话+组探针。
        List<KgNode> nodes = loadCourseNodes(courseId);
        List<KgEdge> prereqEdges = loadPrereqEdges(courseId);
        Set<Long> testable = computeTestableNodeIds(nodes);

        AdaptiveEngine engine = new AdaptiveEngine(nodes, prereqEdges, testable, budget);

        AdaptiveDiagSession session = new AdaptiveDiagSession();
        session.setStudentId(studentId);
        session.setCourseId(courseId);
        session.setStatus(SESSION_IN_PROGRESS);
        session.setCreatedAt(LocalDateTime.now());

        Optional<Long> first = engine.selectNextNode();
        AdaptiveDiagnosticVO vo;
        if (first.isPresent()) {
            ProbeBundle bundle = buildProbe(first.get(), studentId);
            AdaptiveDiagState state = new AdaptiveDiagState(
                    engine.statesView(), engine.getBudgetUsed(),
                    new AdaptiveDiagState.Probe(bundle.nodeId(), bundle.questionIds()),
                    List.of());
            session.setStateJson(state.toJson());
            adaptiveDiagSessionMapper.insert(session);
            vo = notDoneResponse(session.getId(), bundle, progressFrom(engine));
        } else {
            // 空图/空题库/无 UNKNOWN → 一开局即终止
            AdaptiveDiagnosticVO.SummaryVO summary = finalizeSession(session, engine, nodes);
            AdaptiveDiagState state = new AdaptiveDiagState(
                    engine.statesView(), engine.getBudgetUsed(), null, List.of());
            session.setStateJson(state.toJson());
            adaptiveDiagSessionMapper.insert(session);
            vo = doneResponse(session.getId(), summary, progressFrom(engine), courseId);
        }
        return vo;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // answer
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdaptiveDiagnosticVO answer(Long sessionId, AdaptiveAnswerRequest req) {
        // ── 1. 会话存在性 + 属主（service 层反查，对齐 M1 submit 范式）──────────
        AdaptiveDiagSession session = adaptiveDiagSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "诊断会话不存在：sessionId=" + sessionId);
        }
        if (req == null || req.getStudentId() == null
                || !req.getStudentId().equals(session.getStudentId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "会话归属校验失败：不能作答非本人的诊断会话");
        }
        // ── 2. 已放弃不可作答（已完成留待幂等重放分支判定）───────────────────
        int status = session.getStatus() == null ? SESSION_IN_PROGRESS : session.getStatus();
        if (status == SESSION_ABANDONED) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "会话已放弃，不可继续作答");
        }

        AdaptiveDiagState state = AdaptiveDiagState.fromJson(session.getStateJson());

        // ── 3. 入参校验：非空、无重复、无 null questionId ─────────────────────
        List<AdaptiveAnswerRequest.AnswerItem> answers = req.getAnswers();
        if (answers == null || answers.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "作答列表不能为空");
        }
        List<Long> answerQids = new ArrayList<>(answers.size());
        for (AdaptiveAnswerRequest.AnswerItem it : answers) {
            if (it.getQuestionId() == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "作答项缺少 questionId");
            }
            answerQids.add(it.getQuestionId());
        }
        Set<Long> answerSet = new HashSet<>(answerQids);
        if (answerSet.size() != answerQids.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "作答含重复 questionId");
        }

        // ── 4. 分类：当前探针（正常）/ 上一探针（幂等重放）/ 其余（拒绝）──────
        //    正常判分先决条件 status=0（裁决 6）：终局会话 currentProbe 恒 null，天然落幂等/拒绝分支。
        AdaptiveDiagState.Probe currentProbe = state.currentProbe();
        boolean matchesCurrent = status == SESSION_IN_PROGRESS
                && currentProbe != null
                && sameSet(answerSet, currentProbe.questionIds());
        if (matchesCurrent) {
            return processAnswer(session, state, currentProbe, answers);
        }

        List<AdaptiveDiagState.ProbeRecord> history = state.probeHistory();
        AdaptiveDiagState.ProbeRecord last = history.isEmpty() ? null : history.get(history.size() - 1);
        boolean matchesLast = last != null && sameSet(answerSet, last.questionIds());
        if (matchesLast) {
            // 幂等重放：客户端重试/双击——不重判分/落库/动引擎，按当前会话状态重建响应
            return replayResponse(session, state);
        }

        throw new BusinessException(ResultCode.BAD_REQUEST,
                "作答题目集合与当前探针不一致（部分/外来题/更早探针一律拒绝）");
    }

    /** 正常判分处理（判分→落库→applyAnswers→记账传播→选下一探针/终止）。 */
    private AdaptiveDiagnosticVO processAnswer(AdaptiveDiagSession session,
                                               AdaptiveDiagState state,
                                               AdaptiveDiagState.Probe probe,
                                               List<AdaptiveAnswerRequest.AnswerItem> answers) {
        Long studentId = session.getStudentId();
        Long courseId = session.getCourseId();
        List<Long> frozen = probe.questionIds();

        // ── 0. CAS 乐观锁基线：记住进入时读到的 state_json（写回前作 WHERE 条件）──
        //    正常作答必把 state_json 推进到新值，故它在每次成功作答上必变（见 casUpdateOrThrow）。
        String casBaselineStateJson = session.getStateJson();

        // ── 判分（服务端，复用 AnswerGrader）+ 写 answer_record（scene=1）───────
        Map<Long, Question> qById = loadQuestions(frozen);
        Map<Long, Set<String>> correctKeys = loadCorrectKeys(frozen);
        AnswerGrader grader = new AnswerGrader();
        List<GradedAnswer> graded = new ArrayList<>(answers.size());
        boolean pass = true; // 全对才 PASS（探针只含客观题，均可判分）
        LocalDateTime now = LocalDateTime.now();
        for (AdaptiveAnswerRequest.AnswerItem it : answers) {
            Long qid = it.getQuestionId();
            int type = typeOf(qById.get(qid));
            Set<String> keys = correctKeys.getOrDefault(qid, Set.of());
            AnswerGrader.GradeResult result = grader.grade(type, it.getStudentAnswer(), keys);
            boolean correct = Boolean.TRUE.equals(result.correct());
            if (!correct) {
                pass = false;
            }
            AnswerRecord record = new AnswerRecord();
            record.setStudentId(studentId);
            record.setCourseId(courseId);
            record.setQuestionId(qid);
            record.setStudentAnswer(it.getStudentAnswer());
            record.setIsCorrect(correct ? IS_CORRECT : IS_WRONG);
            record.setAnsweredAt(now);
            record.setScene(SCENE_DIAGNOSTIC);
            record.setSessionId(session.getId());
            answerRecordMapper.insert(record);
            if (result.isGradeable()) {
                graded.add(new GradedAnswer(qid, correct));
            }
        }

        // ── 实测掌握度：唯一入口 applyAnswers（与固定卷完全同构，同事务）──────
        masteryService.applyAnswers(studentId, courseId, graded);

        // ── 恢复引擎（加载图 + 恢复状态；不重放传播）→ 记账 → 传播 ──────────
        List<KgNode> nodes = loadCourseNodes(courseId);
        List<KgEdge> prereqEdges = loadPrereqEdges(courseId);
        Set<Long> testable = computeTestableNodeIds(nodes);
        AdaptiveEngine engine = new AdaptiveEngine(nodes, prereqEdges, testable, budget,
                state.nodeStates(), state.budgetUsed());
        engine.recordProbeResult(probe.nodeId(), pass, frozen.size());

        List<AdaptiveDiagState.ProbeRecord> newHistory = new ArrayList<>(state.probeHistory());
        newHistory.add(new AdaptiveDiagState.ProbeRecord(probe.nodeId(), frozen, pass));

        // ── 选下一探针 / 终止 ──────────────────────────────────────────────
        Optional<Long> next = engine.selectNextNode();
        AdaptiveDiagnosticVO vo;
        if (next.isPresent()) {
            ProbeBundle bundle = buildProbe(next.get(), studentId);
            AdaptiveDiagState newState = new AdaptiveDiagState(
                    engine.statesView(), engine.getBudgetUsed(),
                    new AdaptiveDiagState.Probe(bundle.nodeId(), bundle.questionIds()),
                    newHistory);
            session.setStateJson(newState.toJson());
            casUpdateOrThrow(session, casBaselineStateJson);
            vo = notDoneResponse(session.getId(), bundle, progressFrom(engine));
        } else {
            AdaptiveDiagnosticVO.SummaryVO summary = finalizeSession(session, engine, nodes);
            AdaptiveDiagState newState = new AdaptiveDiagState(
                    engine.statesView(), engine.getBudgetUsed(), null, newHistory);
            session.setStateJson(newState.toJson());
            casUpdateOrThrow(session, casBaselineStateJson);
            vo = doneResponse(session.getId(), summary, progressFrom(engine), courseId);
        }
        return vo;
    }

    /**
     * 并发安全写回（CAS 乐观锁）：以进入 {@code answer} 时读到的 {@code state_json} 为基线做条件更新。
     *
     * <pre>UPDATE adaptive_diag_session
     *   SET status=?, state_json=&lt;新&gt;, finished_at=?  (由 session 非空字段生成)
     * WHERE id=? AND status=0 AND state_json=&lt;进入时的旧值&gt;</pre>
     *
     * <p><b>为何 state_json 足以作 CAS 基线</b>：每次正常作答都会把 state_json 从"进入时的值"
     * 改写为"含新探针冻结（下一探针题集）/新 probeHistory 追加/新 budgetUsed/传播后新 nodeStates"
     * 的值——即成功作答与 state_json 变更严格一一对应，故它在每次成功作答上<b>必变</b>
     * （即便 status 仍为 0 的多轮作答亦然，弥补了 status 单值不足以做 CAS 的缺口）。
     *
     * <p><b>并发语义</b>：同一探针"双击"两个在途 answer 同读 status=0 / 同一 state_json，各自判分。
     * 先提交者把 state_json 推进到新值（affected=1）；后提交者的 WHERE 匹配 0 行（state_json 已非旧值）
     * → 抛 {@link BusinessException} → 异常传播令 {@code @Transactional} 回滚其已插入的
     * answer_record 与 applyAnswers 的 EWMA 写入。故 EWMA/记账/answer_record 各恰施加一次。
     *
     * <p>字符串列名 UpdateWrapper（非 LambdaUpdateWrapper.set）：对齐 {@code PracticeServiceImpl}，
     * 纯 Mockito 单测无 MyBatis 元数据缓存亦不报错。SET 子句由 {@code session} 非空字段生成
     * （id 作 @TableId 不入 SET）。
     *
     * @param session            已置好新 status/state_json/finishedAt 的会话（作 SET 来源）
     * @param baselineStateJson  进入 answer 时读到的旧 state_json（作 WHERE 乐观锁基线）
     * @throws BusinessException affected=0（并发抢占失败），令事务回滚本次已写入
     */
    private void casUpdateOrThrow(AdaptiveDiagSession session, String baselineStateJson) {
        int affected = adaptiveDiagSessionMapper.update(session,
                new UpdateWrapper<AdaptiveDiagSession>()
                        .eq("id", session.getId())
                        .eq("status", SESSION_IN_PROGRESS)
                        .eq("state_json", baselineStateJson));
        if (affected == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "并发作答冲突：该探针已被其他请求处理，本次作答未生效（请刷新后重试）");
        }
    }

    /** 幂等重放：按当前会话状态（不动引擎、不写库）重建响应。 */
    private AdaptiveDiagnosticVO replayResponse(AdaptiveDiagSession session, AdaptiveDiagState state) {
        AdaptiveDiagnosticVO.ProgressVO progress = progressFromState(state);
        boolean terminated = (session.getStatus() != null && session.getStatus() == SESSION_DONE)
                || state.currentProbe() == null;
        if (terminated) {
            AdaptiveDiagnosticVO.SummaryVO summary = summaryOf(countStates(state.nodeStates()));
            return doneResponse(session.getId(), summary, progress, session.getCourseId());
        }
        // 进行中：重建当前探针 VO（读题脱敏，不写库）
        AdaptiveDiagState.Probe probe = state.currentProbe();
        List<Question> questions = orderedQuestions(probe.questionIds());
        AdaptiveDiagnosticVO.ProbeVO probeVO = new AdaptiveDiagnosticVO.ProbeVO();
        probeVO.setNodeId(probe.nodeId());
        probeVO.setQuestions(sanitize(questions));
        AdaptiveDiagnosticVO vo = new AdaptiveDiagnosticVO();
        vo.setSessionId(session.getId());
        vo.setDone(false);
        vo.setProbe(probeVO);
        vo.setProgress(progress);
        return vo;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // abandon
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void abandon(Long sessionId, Long studentId) {
        AdaptiveDiagSession session = adaptiveDiagSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "诊断会话不存在：sessionId=" + sessionId);
        }
        if (studentId == null || !studentId.equals(session.getStudentId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "会话归属校验失败：不能放弃非本人的诊断会话");
        }
        int status = session.getStatus() == null ? SESSION_IN_PROGRESS : session.getStatus();
        if (status == SESSION_ABANDONED) {
            return; // 幂等：已放弃 no-op
        }
        if (status == SESSION_DONE) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "已完成的诊断不可放弃");
        }
        // status=0 → 条件更新置 2（防 lost-update：并发 answer 赢者提交后 abandon 不得回退）。
        // 已实测掌握度天然保留（applyAnswers 已即时落库，无需补偿）；
        // 推断落库不触发（计划 §2.4：abandon=status 2+已实测保留，推断初值只在正常终止时写）。
        session.setStatus(SESSION_ABANDONED);
        session.setFinishedAt(LocalDateTime.now());
        // 清空在途探针（T3 契约：放弃后 currentProbe=null），保留已测状态与历史
        if (session.getStateJson() != null) {
            AdaptiveDiagState prevState = AdaptiveDiagState.fromJson(session.getStateJson());
            session.setStateJson(new AdaptiveDiagState(
                    prevState.nodeStates(), prevState.budgetUsed(), null, prevState.probeHistory()).toJson());
        }
        // 条件更新：eq id + eq status=0（字符串列名，对齐 answer 路径 casUpdateOrThrow 写法）。
        // 0 行时重查 status 分派：status=2 → 幂等成功；status=1 → BAD_REQUEST（已完成不可放弃）。
        int affected = adaptiveDiagSessionMapper.update(session,
                new UpdateWrapper<AdaptiveDiagSession>()
                        .eq("id", session.getId())
                        .eq("status", SESSION_IN_PROGRESS));
        if (affected == 0) {
            AdaptiveDiagSession current = adaptiveDiagSessionMapper.selectById(sessionId);
            int currentStatus = (current == null || current.getStatus() == null)
                    ? SESSION_IN_PROGRESS : current.getStatus();
            if (currentStatus == SESSION_ABANDONED) {
                return; // 幂等：并发下另一 abandon 请求已先行完成
            }
            throw new BusinessException(ResultCode.BAD_REQUEST, "已完成的诊断不可放弃");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 图 / 可实测集 加载
    // ══════════════════════════════════════════════════════════════════════════

    private List<KgNode> loadCourseNodes(Long courseId) {
        return kgNodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>().eq(KgNode::getCourseId, courseId));
    }

    /** 一次查前置边（relation_type=1），供内存邻接表使用（循环内绝不查库）。 */
    private List<KgEdge> loadPrereqEdges(Long courseId) {
        return kgEdgeMapper.selectList(
                new LambdaQueryWrapper<KgEdge>()
                        .eq(KgEdge::getCourseId, courseId)
                        .eq(KgEdge::getRelationType, PrereqDirection.RELATION_TYPE_PREREQUISITE));
    }

    /**
     * 可实测节点集 = 课程节点中"≥1 道 APPROVED 客观题（question_node 关联）"的节点。
     * 批量 IN 一次算出（对齐 M1 T8 防 N+1）。
     */
    private Set<Long> computeTestableNodeIds(List<KgNode> courseNodes) {
        List<Long> nodeIds = courseNodes.stream().map(KgNode::getId).collect(Collectors.toList());
        if (nodeIds.isEmpty()) {
            return Set.of();
        }
        List<QuestionNode> links = questionNodeMapper.selectList(
                new LambdaQueryWrapper<QuestionNode>().in(QuestionNode::getNodeId, nodeIds));
        if (links.isEmpty()) {
            return Set.of();
        }
        List<Long> questionIds = links.stream()
                .map(QuestionNode::getQuestionId).distinct().collect(Collectors.toList());
        List<Question> approved = questionMapper.selectList(
                new LambdaQueryWrapper<Question>()
                        .in(Question::getId, questionIds)
                        .eq(Question::getStatus, QuestionStatus.APPROVED));
        Set<Long> approvedObjectiveIds = approved.stream()
                .filter(q -> isObjective(q.getType()))
                .map(Question::getId)
                .collect(Collectors.toSet());
        Set<Long> testable = new HashSet<>();
        for (QuestionNode link : links) {
            if (approvedObjectiveIds.contains(link.getQuestionId())) {
                testable.add(link.getNodeId());
            }
        }
        return testable;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 组探针
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 为引擎选中的节点组探针：近期排除 → 共享组卷（仅客观题）→ 结构性脱敏。
     * nodeId ∈ testable 保证 ≥1 道 APPROVED 客观题，故 pick 必非空。
     */
    private ProbeBundle buildProbe(Long nodeId, Long studentId) {
        Set<Long> recent = recentQuestionIds(studentId);
        List<Question> selected = questionPicker.pick(
                nodeId, PROBE_QUESTION_COUNT, recent, q -> isObjective(q.getType()));
        if (selected.isEmpty()) {
            // 防御：testable 已保证有题，理论不达；若达则说明可实测集与题库不一致（bug）
            throw new IllegalStateException("可实测节点组探针为空题（testable 与题库不一致）：nodeId=" + nodeId);
        }
        List<Long> qids = selected.stream().map(Question::getId).collect(Collectors.toList());
        return new ProbeBundle(nodeId, qids, sanitize(selected));
    }

    /** 近期已答题 ID（recencyDays 窗口，任意场景），供组卷排除。 */
    private Set<Long> recentQuestionIds(Long studentId) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(recencyDays);
        List<AnswerRecord> recent = answerRecordMapper.selectList(
                new LambdaQueryWrapper<AnswerRecord>()
                        .eq(AnswerRecord::getStudentId, studentId)
                        .ge(AnswerRecord::getAnsweredAt, cutoff));
        return recent.stream().map(AnswerRecord::getQuestionId).collect(Collectors.toSet());
    }

    /**
     * 结构性脱敏：只暴露题干 + 选项 key/text；<b>不含 answer/isCorrect/pointNodeCode</b>，
     * 且<b>不下发 nodeName/chapter</b>（探针防引导作弊，已锁决策）。
     */
    private List<PaperQuestionVO> sanitize(List<Question> questions) {
        if (questions.isEmpty()) {
            return List.of();
        }
        List<Long> ids = questions.stream().map(Question::getId).collect(Collectors.toList());
        List<QuestionOption> allOpts = questionOptionMapper.selectList(
                new LambdaQueryWrapper<QuestionOption>().in(QuestionOption::getQuestionId, ids));
        Map<Long, List<QuestionOption>> byQuestion = allOpts.stream()
                .collect(Collectors.groupingBy(QuestionOption::getQuestionId));
        return questions.stream().map(q -> {
            PaperQuestionVO vo = new PaperQuestionVO();
            vo.setQuestionId(q.getId());
            vo.setStem(q.getStem());
            vo.setType(q.getType());
            // 不设 chapter：探针不透露章节归属
            List<PaperQuestionVO.OptionVO> optVOs = byQuestion.getOrDefault(q.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(QuestionOption::getOptionKey))
                    .map(o -> {
                        PaperQuestionVO.OptionVO ov = new PaperQuestionVO.OptionVO();
                        ov.setKey(o.getOptionKey());
                        ov.setText(o.getOptionText());
                        return ov; // 绝不设置 isCorrect / pointNodeCode
                    })
                    .collect(Collectors.toList());
            vo.setOptions(optVOs);
            return vo;
        }).collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 终止收尾（T4 桩，T5 接手）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 终止收尾（T5 完整实现）：置 status=1 + finished_at；落库推断保守初值；写快照；汇总节点明细。
     *
     * <h3>推断落库（M2 §2.2）</h3>
     * <ul>
     *   <li>INFERRED_PASS → score=70，INFERRED_FAIL → score=35（常量写入，非 EWMA，不违反掌握度计算红线）。</li>
     *   <li>只填空不覆盖：批量 IN 查既有 student_mastery 行（不循环查库），已有行跳过。</li>
     *   <li>TESTED 节点已由 {@link MasteryService#applyAnswers} 即时落库，UNKNOWN 不落库。</li>
     *   <li>level 复用 {@link MasteryService#levelOf}（不重写阈值逻辑）。</li>
     * </ul>
     *
     * <h3>快照</h3>
     * <p>每个实际插入的推断节点追加一条 mastery_snapshot（复用 {@link MasteryService#appendSnapshot}）。
     * 已有行跳过 → 快照亦不重复写。全 UNKNOWN / 全 TESTED 时推断落库为空集 → 本方法不写任何快照
     * （TESTED 节点快照已由 applyAnswers 按答题次序逐题追加，此处不补写，避免重复）。
     *
     * <h3>节点明细</h3>
     * <p>courseNodes 由调用方传入（已在本事务内查过一次，防 N+1），按 nodeId 升序排列。
     * 包含全部节点（UNKNOWN / TESTED / INFERRED），state 取 {@link NodeState#name()}。
     *
     * @param session     会话（本方法置 status=1 / finishedAt；state_json 由调用方序列化写回）
     * @param engine      已记账传播后的引擎（取 summary + statesView）
     * @param courseNodes 课程全节点（由 loadCourseNodes 在当前事务查出，传入复用，不再查库）
     */
    private AdaptiveDiagnosticVO.SummaryVO finalizeSession(AdaptiveDiagSession session,
                                                           AdaptiveEngine engine,
                                                           List<KgNode> courseNodes) {
        session.setStatus(SESSION_DONE);
        session.setFinishedAt(LocalDateTime.now());

        Long studentId = session.getStudentId();
        Long courseId = session.getCourseId();
        Map<Long, NodeState> nodeStates = engine.statesView();
        AdaptiveEngine.Summary summary = engine.summary();

        // ── 1. 收集推断节点 ID（批量查既有行用）─────────────────────────────
        List<Long> inferredNodeIds = nodeStates.entrySet().stream()
                .filter(e -> e.getValue().isInferred())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // ── 2. 一次 IN 查：哪些推断节点已有 student_mastery 行（不覆盖）──────
        Set<Long> existingNodeIds = new HashSet<>();
        if (!inferredNodeIds.isEmpty()) {
            List<StudentMastery> existing = studentMasteryMapper.selectList(
                    new LambdaQueryWrapper<StudentMastery>()
                            .eq(StudentMastery::getStudentId, studentId)
                            .eq(StudentMastery::getCourseId, courseId)
                            .in(StudentMastery::getNodeId, inferredNodeIds));
            for (StudentMastery sm : existing) {
                existingNodeIds.add(sm.getNodeId());
            }
        }

        // ── 3. 为无既有行的推断节点写 student_mastery + mastery_snapshot ─────
        for (Map.Entry<Long, NodeState> entry : nodeStates.entrySet()) {
            Long nodeId = entry.getKey();
            NodeState state = entry.getValue();
            if (!state.isInferred()) {
                continue; // TESTED 已由 applyAnswers 处理；UNKNOWN 不落库
            }
            if (existingNodeIds.contains(nodeId)) {
                continue; // 已有行，绝不覆盖（比如之前练习已建立掌握度）
            }
            int rawScore = (state == NodeState.INFERRED_PASS) ? INFERRED_PASS_SCORE : INFERRED_FAIL_SCORE;
            int level = masteryService.levelOf(rawScore);
            BigDecimal score = BigDecimal.valueOf(rawScore).setScale(2, RoundingMode.HALF_UP);

            StudentMastery row = new StudentMastery();
            row.setStudentId(studentId);
            row.setCourseId(courseId);
            row.setNodeId(nodeId);
            row.setMasteryScore(score);
            row.setMasteryLevel(level);
            studentMasteryMapper.insert(row);

            // 快照追加（复用 MasteryService 既有机制，对齐 applyAnswers 快照粒度）
            masteryService.appendSnapshot(studentId, courseId, nodeId, score, level);
        }

        // ── 4. 汇总节点明细（全课程节点，含 UNKNOWN / TESTED / INFERRED）──────
        //    courseNodes 传入复用（本事务已查一次），此处不再查库（防 N+1）。
        Map<Long, KgNode> nodeById = new HashMap<>();
        for (KgNode n : courseNodes) {
            nodeById.put(n.getId(), n);
        }
        List<AdaptiveDiagnosticVO.NodeDetailVO> nodeDetails = nodeStates.entrySet().stream()
                .map(e -> {
                    KgNode kg = nodeById.get(e.getKey());
                    AdaptiveDiagnosticVO.NodeDetailVO detail = new AdaptiveDiagnosticVO.NodeDetailVO();
                    detail.setNodeId(e.getKey());
                    if (kg != null) {
                        detail.setNodeCode(kg.getNodeCode());
                        detail.setName(kg.getName());
                    }
                    detail.setState(e.getValue().name());
                    return detail;
                })
                .sorted(Comparator.comparing(AdaptiveDiagnosticVO.NodeDetailVO::getNodeId))
                .collect(Collectors.toList());

        AdaptiveDiagnosticVO.SummaryVO vo = new AdaptiveDiagnosticVO.SummaryVO();
        vo.setTested(summary.tested());
        vo.setInferredPass(summary.inferredPass());
        vo.setInferredFail(summary.inferredFail());
        vo.setUnknown(summary.unknown());
        vo.setNodes(nodeDetails);
        return vo;
    }

    private AdaptiveDiagnosticVO.SummaryVO summaryOf(AdaptiveEngine.Summary s) {
        AdaptiveDiagnosticVO.SummaryVO vo = new AdaptiveDiagnosticVO.SummaryVO();
        vo.setTested(s.tested());
        vo.setInferredPass(s.inferredPass());
        vo.setInferredFail(s.inferredFail());
        vo.setUnknown(s.unknown());
        vo.setNodes(new ArrayList<>()); // 幂等重放轻量路径（无需节点明细）
        return vo;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 响应装配
    // ══════════════════════════════════════════════════════════════════════════

    private AdaptiveDiagnosticVO notDoneResponse(Long sessionId, ProbeBundle bundle,
                                                 AdaptiveDiagnosticVO.ProgressVO progress) {
        AdaptiveDiagnosticVO.ProbeVO probeVO = new AdaptiveDiagnosticVO.ProbeVO();
        probeVO.setNodeId(bundle.nodeId());
        probeVO.setQuestions(bundle.questionVOs());
        AdaptiveDiagnosticVO vo = new AdaptiveDiagnosticVO();
        vo.setSessionId(sessionId);
        vo.setDone(false);
        vo.setProbe(probeVO);
        vo.setProgress(progress);
        return vo;
    }

    private AdaptiveDiagnosticVO doneResponse(Long sessionId, AdaptiveDiagnosticVO.SummaryVO summary,
                                              AdaptiveDiagnosticVO.ProgressVO progress, Long courseId) {
        AdaptiveDiagnosticVO vo = new AdaptiveDiagnosticVO();
        vo.setSessionId(sessionId);
        vo.setDone(true);
        vo.setSummary(summary);
        vo.setProgress(progress);
        vo.setResultUrl(resultUrl(courseId));
        return vo;
    }

    /** 结果页地址（T4 固定，T8 增强）。 */
    private String resultUrl(Long courseId) {
        return "/diagnostic/result?courseId=" + courseId;
    }

    private AdaptiveDiagnosticVO.ProgressVO progressFrom(AdaptiveEngine engine) {
        AdaptiveEngine.Summary s = engine.summary();
        AdaptiveDiagnosticVO.ProgressVO p = new AdaptiveDiagnosticVO.ProgressVO();
        p.setTested(s.tested());
        p.setInferred(s.inferredPass() + s.inferredFail());
        p.setUnknown(s.unknown());
        p.setBudgetLeft(Math.max(0, budget - engine.getBudgetUsed()));
        return p;
    }

    /** 从持久化状态直接算进度（幂等重放用，不重建引擎）。 */
    private AdaptiveDiagnosticVO.ProgressVO progressFromState(AdaptiveDiagState state) {
        AdaptiveEngine.Summary s = countStates(state.nodeStates());
        AdaptiveDiagnosticVO.ProgressVO p = new AdaptiveDiagnosticVO.ProgressVO();
        p.setTested(s.tested());
        p.setInferred(s.inferredPass() + s.inferredFail());
        p.setUnknown(s.unknown());
        p.setBudgetLeft(Math.max(0, budget - state.budgetUsed()));
        return p;
    }

    /** 从状态 Map 统计四类计数（复用引擎 Summary 形状）。 */
    private AdaptiveEngine.Summary countStates(Map<Long, NodeState> states) {
        int tested = 0;
        int inferredPass = 0;
        int inferredFail = 0;
        int unknown = 0;
        for (NodeState st : states.values()) {
            switch (st) {
                case TESTED_PASS, TESTED_FAIL -> tested++;
                case INFERRED_PASS -> inferredPass++;
                case INFERRED_FAIL -> inferredFail++;
                case UNKNOWN -> unknown++;
            }
        }
        return new AdaptiveEngine.Summary(tested, inferredPass, inferredFail, unknown);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 判分辅助
    // ══════════════════════════════════════════════════════════════════════════

    /** 批量查题（一次 IN）：questionId → Question。 */
    private Map<Long, Question> loadQuestions(List<Long> questionIds) {
        List<Question> questions = questionMapper.selectList(
                new LambdaQueryWrapper<Question>().in(Question::getId, questionIds));
        Map<Long, Question> map = new HashMap<>();
        for (Question q : questions) {
            map.put(q.getId(), q);
        }
        return map;
    }

    /** 按冻结顺序返回题目（幂等重放重建探针 VO 用）。 */
    private List<Question> orderedQuestions(List<Long> questionIds) {
        Map<Long, Question> map = loadQuestions(questionIds);
        List<Question> ordered = new ArrayList<>(questionIds.size());
        for (Long id : questionIds) {
            Question q = map.get(id);
            if (q != null) {
                ordered.add(q);
            }
        }
        return ordered;
    }

    /** 批量查正确选项键：questionId → Set&lt;optionKey&gt;（is_correct=1）。 */
    private Map<Long, Set<String>> loadCorrectKeys(List<Long> questionIds) {
        List<QuestionOption> options = questionOptionMapper.selectList(
                new LambdaQueryWrapper<QuestionOption>().in(QuestionOption::getQuestionId, questionIds));
        Map<Long, Set<String>> map = new HashMap<>();
        for (QuestionOption opt : options) {
            if (Integer.valueOf(IS_CORRECT).equals(opt.getIsCorrect())) {
                map.computeIfAbsent(opt.getQuestionId(), k -> new HashSet<>()).add(opt.getOptionKey());
            }
        }
        return map;
    }

    /** 题型（缺失默认单选，安全兜底）。 */
    private int typeOf(Question question) {
        return (question == null || question.getType() == null)
                ? QuestionType.SINGLE : question.getType();
    }

    /** 客观题：单选/多选/判断（排除简答，探针不含简答）。 */
    private boolean isObjective(Integer type) {
        return type != null && type >= QuestionType.SINGLE && type <= QuestionType.TRUE_FALSE;
    }

    /** 两集合是否相等（顺序无关）。 */
    private boolean sameSet(Set<Long> a, List<Long> b) {
        return a.size() == b.size() && a.containsAll(b);
    }

    /** 探针组卷产物：被探节点 + 冻结题 id + 脱敏题目 VO。 */
    private record ProbeBundle(Long nodeId, List<Long> questionIds, List<PaperQuestionVO> questionVOs) {}
}
