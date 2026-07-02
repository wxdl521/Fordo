package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.GradedAnswer;
import com.wenjin.dto.PaperQuestionVO;
import com.wenjin.dto.PathGenerateRequest;
import com.wenjin.dto.PracticeStartVO;
import com.wenjin.dto.PracticeSubmitRequest;
import com.wenjin.dto.PracticeSubmitVO;
import com.wenjin.entity.AnswerRecord;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.LearningPath;
import com.wenjin.entity.LearningPathItem;
import com.wenjin.entity.PracticeSession;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.AnswerRecordMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.LearningPathItemMapper;
import com.wenjin.mapper.LearningPathMapper;
import com.wenjin.mapper.PracticeSessionMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryService;
import com.wenjin.service.PathService;
import com.wenjin.service.PracticeService;
import com.wenjin.support.AnswerGrader;
import com.wenjin.support.QuestionStatus;
import com.wenjin.support.QuestionType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 节点练习服务实现（M1 练习闭环 T3 组卷 + T4 提交判分 + T5 distractor 归因 + 自动通过判定）。
 *
 * <h3>提交流程（submit，单事务）</h3>
 * <ol>
 *   <li>校验会话存在、归属（req.studentId = session.studentId）、题目 ⊆ 冻结 question_ids；</li>
 *   <li>status=1 幂等重放：从 answer_record 重建上次结果，不重复写库/更掌握度；</li>
 *   <li>服务端判分（复用 {@link AnswerGrader}），简答 correct=null 不进 EWMA；</li>
 *   <li>写 answer_record（scene=2, session_id），调 MasteryService.applyAnswers；</li>
 *   <li>distractor 归因：按错选项 pointNodeCode 聚合计数，命中 ≥ distractorThreshold 者
 *       输出到 weakPrerequisites（T5）；</li>
 *   <li>自动通过判定：若 session.pathItemId 非空且目标节点掌握度 ≥ masteredThreshold，
 *       将路径步骤置完成（T5）；</li>
 *   <li>会话置 status=1，返回判分明细 + 目标节点掌握度变化。</li>
 * </ol>
 *
 * <h3>组卷策略</h3>
 * <ol>
 *   <li>从 {@code question_node} 取该节点 weight=1 关联题，过滤 status=APPROVED；</li>
 *   <li>排除该学生 {@code recencyDays} 天内在任意场景答过的题（查 {@code answer_record}）；</li>
 *   <li>不足 {@code effectiveSize} 时放宽到 weight=2（同样过滤 status + 近期）；</li>
 *   <li>≥1 题即可开会话；0 题时抛 {@link BusinessException} 提示题库不足；</li>
 *   <li>轻度难度分层：优先覆盖难度 2–4 各至少一题（难度排序取样简化版）。</li>
 * </ol>
 *
 * <h3>注意</h3>
 * <ul>
 *   <li>Controller/鉴权由 T7 实现，此层不调用 AccessGuard。</li>
 *   <li>UpdateWrapper 字符串列名写法与 PathServiceImpl 保持一致（Lambda 缓存单测会报错）。</li>
 * </ul>
 */
@Service
public class PracticeServiceImpl implements PracticeService {

    /** practice_session.status：进行中 */
    private static final int SESSION_IN_PROGRESS = 0;
    /** practice_session.status：已提交 */
    private static final int SESSION_SUBMITTED = 1;
    /** answer_record.scene：节点练习 */
    private static final int SCENE_PRACTICE = 2;
    /** answer_record.is_correct：正确 */
    private static final int IS_CORRECT = 1;
    /** answer_record.is_correct：错误（简答亦写 0，对齐诊断侧"待 AI 判定"语义） */
    private static final int IS_WRONG = 0;
    /** learning_path_item.status：已完成 */
    private static final int ITEM_DONE = 1;
    /** learning_path.status：当前有效 */
    private static final int PATH_ACTIVE = 1;

    private final QuestionNodeMapper questionNodeMapper;
    private final QuestionMapper questionMapper;
    private final AnswerRecordMapper answerRecordMapper;
    private final KgNodeMapper kgNodeMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final PracticeSessionMapper practiceSessionMapper;
    private final MasteryService masteryService;
    private final StudentMasteryMapper studentMasteryMapper;
    private final LearningPathItemMapper learningPathItemMapper;
    private final PathService pathService;
    private final LearningPathMapper learningPathMapper;

    /** 每会话默认题数（配置项 wenjin.practice.size）。 */
    @Value("${wenjin.practice.size:5}")
    private int defaultSize;

    /** 每会话最大题数上限（配置项 wenjin.practice.max-size）。 */
    @Value("${wenjin.practice.max-size:10}")
    private int maxSize;

    /** 组卷近期排除窗口（天数，配置项 wenjin.practice.recency-days）。 */
    @Value("${wenjin.practice.recency-days:7}")
    private int recencyDays;

    /**
     * distractor 归因命中阈值（配置项 wenjin.practice.distractor-threshold）。
     * 同一前置节点被错选命中次数 ≥ 此值才输出到 weakPrerequisites。
     */
    @Value("${wenjin.practice.distractor-threshold:2}")
    private int distractorThreshold;

    /**
     * 掌握度"已掌握"判定阈值（配置项 wenjin.mastery.mastered-threshold，与 PathServiceImpl 共用同一配置项）。
     * 提交后目标节点掌握度 ≥ 此值时，自动将路径步骤置完成。
     */
    @Value("${wenjin.mastery.mastered-threshold:75}")
    private double masteredThreshold;

    /**
     * 掌握度"薄弱"下界（配置项 wenjin.mastery.weak-threshold，与 MasteryServiceImpl 共用同一配置项）。
     * 路径重算条件 (b)：目标节点练后分值 &lt; 此值 且 不在当前路径中，则触发重算。
     */
    @Value("${wenjin.mastery.weak-threshold:40}")
    private double weakThreshold;

    public PracticeServiceImpl(QuestionNodeMapper questionNodeMapper,
                               QuestionMapper questionMapper,
                               AnswerRecordMapper answerRecordMapper,
                               KgNodeMapper kgNodeMapper,
                               QuestionOptionMapper questionOptionMapper,
                               PracticeSessionMapper practiceSessionMapper,
                               MasteryService masteryService,
                               StudentMasteryMapper studentMasteryMapper,
                               LearningPathItemMapper learningPathItemMapper,
                               PathService pathService,
                               LearningPathMapper learningPathMapper) {
        this.questionNodeMapper = questionNodeMapper;
        this.questionMapper = questionMapper;
        this.answerRecordMapper = answerRecordMapper;
        this.kgNodeMapper = kgNodeMapper;
        this.questionOptionMapper = questionOptionMapper;
        this.practiceSessionMapper = practiceSessionMapper;
        this.masteryService = masteryService;
        this.studentMasteryMapper = studentMasteryMapper;
        this.learningPathItemMapper = learningPathItemMapper;
        this.pathService = pathService;
        this.learningPathMapper = learningPathMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PracticeStartVO start(Long studentId, Long courseId, Long nodeId, Integer size) {
        // ── 1. 解析 size：null 取默认值，超上限夹紧 ─────────────────────────
        int effectiveSize = (size == null) ? defaultSize : Math.min(size, maxSize);

        // ── 2. 校验节点存在 ───────────────────────────────────────────────────
        KgNode node = kgNodeMapper.selectById(nodeId);
        if (node == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "知识点不存在：nodeId=" + nodeId);
        }

        // ── 3. 查询该学生近期（recencyDays 天内）已答题 ID 集合 ──────────────
        //    任意场景（scene 不限）均算在排除窗口内
        LocalDateTime cutoff = LocalDateTime.now().minusDays(recencyDays);
        List<AnswerRecord> recentRecords = answerRecordMapper.selectList(
                new LambdaQueryWrapper<AnswerRecord>()
                        .eq(AnswerRecord::getStudentId, studentId)
                        .ge(AnswerRecord::getAnsweredAt, cutoff));
        Set<Long> recentQuestionIds = recentRecords.stream()
                .map(AnswerRecord::getQuestionId)
                .collect(Collectors.toSet());

        // ── 4. 取 weight=1 已审核题池（扣除近期已答） ────────────────────────
        List<Question> pool = fetchApprovedPool(nodeId, 1, recentQuestionIds);
        Set<Long> poolIds = pool.stream().map(Question::getId).collect(Collectors.toSet());

        // ── 5. 若 weight=1 不足，放宽到 weight=2 补充 ────────────────────────
        if (pool.size() < effectiveSize) {
            List<Question> w2Pool = fetchApprovedPool(nodeId, 2, recentQuestionIds);
            for (Question q : w2Pool) {
                if (poolIds.add(q.getId())) { // 去重（题可能同时有 weight=1 和 weight=2 链接）
                    pool.add(q);
                }
            }
        }

        // ── 6. 0 题可用 → 抛业务异常 ─────────────────────────────────────────
        if (pool.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "题库不足，该知识点暂无可用题目（已过滤未审核与近期已答）");
        }

        // ── 7. 轻度难度分层取样，最多 effectiveSize 道 ───────────────────────
        List<Question> selected = stratifyPick(pool, effectiveSize);

        // ── 8. 冻结 question_ids，创建 practice_session（status=0） ──────────
        String questionIds = selected.stream()
                .map(q -> String.valueOf(q.getId()))
                .collect(Collectors.joining(","));

        PracticeSession session = new PracticeSession();
        session.setStudentId(studentId);
        session.setCourseId(courseId);
        session.setNodeId(nodeId);
        session.setQuestionIds(questionIds);
        session.setStatus(SESSION_IN_PROGRESS);
        session.setCreatedAt(LocalDateTime.now());
        // submittedAt 留 null（未提交）
        practiceSessionMapper.insert(session);

        // ── 9. 构建脱敏 VO ────────────────────────────────────────────────────
        List<PaperQuestionVO> questionVOs = buildQuestionVOs(selected, node.getChapter());

        PracticeStartVO vo = new PracticeStartVO();
        vo.setSessionId(session.getId());
        PracticeStartVO.NodeRef nodeRef = new PracticeStartVO.NodeRef();
        nodeRef.setNodeId(node.getId());
        nodeRef.setNodeCode(node.getNodeCode());
        nodeRef.setName(node.getName());
        vo.setNode(nodeRef);
        vo.setQuestions(questionVOs);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PracticeSubmitVO submit(Long sessionId, PracticeSubmitRequest req) {
        // ── 1. 校验会话存在 ───────────────────────────────────────────────────
        PracticeSession session = practiceSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND,
                    "练习会话不存在：sessionId=" + sessionId);
        }

        // ── 1b. 校验会话归属（service 层防御；AccessGuard.assertSelf 由 T7 Controller 做） ──
        if (req == null || req.getStudentId() == null
                || !req.getStudentId().equals(session.getStudentId())) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "会话归属校验失败：不能提交非本人的练习会话");
        }

        // ── 2. 幂等：已提交会话直接重建上次结果（不写库、不调 applyAnswers） ──
        if (session.getStatus() != null && session.getStatus() == SESSION_SUBMITTED) {
            return rebuildSubmittedResult(session);
        }

        // ── 3. 校验提交题目 ⊆ 冻结的 question_ids ────────────────────────────
        List<PracticeSubmitRequest.AnswerItem> answers = req.getAnswers();
        if (answers == null || answers.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "作答列表不能为空");
        }
        Set<Long> frozenIds = parseFrozenIds(session.getQuestionIds());
        for (PracticeSubmitRequest.AnswerItem item : answers) {
            if (item.getQuestionId() == null || !frozenIds.contains(item.getQuestionId())) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "提交了会话外题目：questionId=" + item.getQuestionId());
            }
        }

        // ── 4. 读目标节点提交前掌握度（applyAnswers 之前） ────────────────────
        StudentMastery before = queryMastery(
                session.getStudentId(), session.getCourseId(), session.getNodeId());

        // ── 5. 服务端判分（复用 AnswerGrader）+ 写 answer_record（scene=2） ──
        List<Long> questionIds = answers.stream()
                .map(PracticeSubmitRequest.AnswerItem::getQuestionId)
                .collect(Collectors.toList());
        Map<Long, Question> questionById = loadQuestions(questionIds);
        // 一次批量查所有选项，在 Java 层分别提取正确选项集合与 distractor 映射（避免 N+1 查询与双次 selectList）
        List<QuestionOption> allOptions = loadAllOptions(questionIds);
        Map<Long, Set<String>> correctKeysByQuestion = extractCorrectKeys(allOptions);
        Map<Long, Map<String, String>> distractorMap = extractDistractorMap(allOptions);

        AnswerGrader grader = new AnswerGrader();
        List<PracticeSubmitVO.GradeItemVO> graded = new ArrayList<>(answers.size());
        List<GradedAnswer> gradedAnswers = new ArrayList<>(answers.size());
        List<AnswerGrader.GradeResult> gradeResults = new ArrayList<>(answers.size());
        LocalDateTime now = LocalDateTime.now();

        for (PracticeSubmitRequest.AnswerItem item : answers) {
            Long questionId = item.getQuestionId();
            String studentAnswer = item.getStudentAnswer();
            Question question = questionById.get(questionId);
            int type = typeOf(question);
            Set<String> correctKeys = correctKeysByQuestion.getOrDefault(questionId, Set.of());

            AnswerGrader.GradeResult result = grader.grade(type, studentAnswer, correctKeys);
            gradeResults.add(result);
            boolean isCorrect = Boolean.TRUE.equals(result.correct());

            // 落库：每题一条答题记录（简答亦留痕，is_correct=0 对齐诊断侧）
            AnswerRecord record = new AnswerRecord();
            record.setStudentId(session.getStudentId());
            record.setCourseId(session.getCourseId());
            record.setQuestionId(questionId);
            record.setStudentAnswer(studentAnswer);
            record.setIsCorrect(isCorrect ? IS_CORRECT : IS_WRONG);
            record.setAnsweredAt(now);
            record.setScene(SCENE_PRACTICE);
            record.setSessionId(session.getId());
            answerRecordMapper.insert(record);

            graded.add(buildGradeItem(questionId, type, result, correctKeys, analysisOf(question)));

            // 简答（correct=null）不进 GradedAnswer → 不进 EWMA
            if (result.isGradeable()) {
                gradedAnswers.add(new GradedAnswer(questionId, isCorrect));
            }
        }

        // ── 6. 掌握度更新：唯一入口 MasteryService.applyAnswers（同事务） ─────
        masteryService.applyAnswers(session.getStudentId(), session.getCourseId(), gradedAnswers);

        // ── 7. 读目标节点提交后掌握度 ─────────────────────────────────────────
        StudentMastery after = queryMastery(
                session.getStudentId(), session.getCourseId(), session.getNodeId());

        // ── 8. distractor 归因聚合（T5）──────────────────────────────────────
        List<PracticeSubmitVO.WeakPrerequisiteVO> weakPreqs =
                aggregateWeakPrerequisites(questionIds, gradeResults, distractorMap, session.getCourseId());

        // ── 9. 自动通过判定（T5）────────────────────────────────────────────
        boolean itemCompleted = maybeCompletePathItem(session, after);

        // ── 10. 路径重算触发（T6）────────────────────────────────────────────
        boolean pathRegenerated = maybeRegeneratePath(session, weakPreqs, after);

        // ── 11. 会话置 status=1，返回结构化结果 ──────────────────────────────
        session.setStatus(SESSION_SUBMITTED);
        session.setSubmittedAt(now);
        practiceSessionMapper.updateById(session);

        PracticeSubmitVO vo = new PracticeSubmitVO();
        vo.setGraded(graded);
        vo.setMasteryBefore(scoreOf(before));
        vo.setMasteryAfter(scoreOf(after));
        vo.setMasteryLevel(levelText(after));
        vo.setWeakPrerequisites(weakPreqs);
        vo.setItemCompleted(itemCompleted);
        vo.setPathRegenerated(pathRegenerated);
        return vo;
    }

    // ── submit 内部方法 ──────────────────────────────────────────────────────

    /**
     * 幂等重放：会话已提交（status=1）时，从 answer_record（scene=2, session_id）
     * 重建上次判分结果——用存档的 studentAnswer 重新过一遍 AnswerGrader（纯函数，
     * 同输入必同输出），不写任何库表、不调 applyAnswers。
     *
     * <p>T5 增量：weakPrerequisites 从存档答案重新聚合（纯读）；
     * itemCompleted 读路径 item 当前状态（不写）。
     *
     * <p>掌握度字段约定：重放时无法恢复"提交前"的分值（已被首次提交覆盖），
     * 故 masteryBefore = masteryAfter = 当前分值。
     */
    private PracticeSubmitVO rebuildSubmittedResult(PracticeSession session) {
        List<AnswerRecord> records = answerRecordMapper.selectList(
                new LambdaQueryWrapper<AnswerRecord>()
                        .eq(AnswerRecord::getSessionId, session.getId())
                        .eq(AnswerRecord::getScene, SCENE_PRACTICE)
                        .orderByAsc(AnswerRecord::getId));

        List<PracticeSubmitVO.GradeItemVO> graded = new ArrayList<>(records.size());
        List<Long> questionIds = new ArrayList<>(records.size());
        List<AnswerGrader.GradeResult> gradeResults = new ArrayList<>(records.size());

        Map<Long, Map<String, String>> distractorMap = new HashMap<>();
        if (!records.isEmpty()) {
            questionIds = records.stream()
                    .map(AnswerRecord::getQuestionId)
                    .collect(Collectors.toList());
            Map<Long, Question> questionById = loadQuestions(questionIds);
            // 一次批量查所有选项，在 Java 层分别提取正确选项集合与 distractor 映射
            List<QuestionOption> allOptions = loadAllOptions(questionIds);
            Map<Long, Set<String>> correctKeysByQuestion = extractCorrectKeys(allOptions);
            distractorMap = extractDistractorMap(allOptions);

            AnswerGrader grader = new AnswerGrader();
            for (AnswerRecord r : records) {
                Question question = questionById.get(r.getQuestionId());
                int type = typeOf(question);
                Set<String> correctKeys = correctKeysByQuestion.getOrDefault(r.getQuestionId(), Set.of());
                AnswerGrader.GradeResult result = grader.grade(type, r.getStudentAnswer(), correctKeys);
                gradeResults.add(result);
                graded.add(buildGradeItem(r.getQuestionId(), type, result, correctKeys, analysisOf(question)));
            }
        }

        // distractor 归因（纯读重算，与首次提交逻辑一致）
        List<PracticeSubmitVO.WeakPrerequisiteVO> weakPreqs = questionIds.isEmpty()
                ? List.of()
                : aggregateWeakPrerequisites(questionIds, gradeResults, distractorMap, session.getCourseId());

        // 路径 item 完成状态（只读，不触发写操作）
        boolean itemCompleted = readItemCompleted(session);

        StudentMastery current = queryMastery(
                session.getStudentId(), session.getCourseId(), session.getNodeId());

        PracticeSubmitVO vo = new PracticeSubmitVO();
        vo.setGraded(graded);
        vo.setMasteryBefore(scoreOf(current));
        vo.setMasteryAfter(scoreOf(current));
        vo.setMasteryLevel(levelText(current));
        vo.setWeakPrerequisites(weakPreqs);
        vo.setItemCompleted(itemCompleted);
        return vo;
    }

    // ── distractor 归因（T5）──────────────────────────────────────────────────

    /**
     * 聚合 distractor 归因：收集所有错选项的 pointNodeCode，按 nodeCode 计数，
     * 命中次数 ≥ {@link #distractorThreshold} 的前置节点即为"暴露的薄弱前置"。
     *
     * @param questionIds  按答题顺序排列的题目 ID（与 gradeResults 下标对应）
     * @param gradeResults 对应的判分结果（含 wrongChosenKeys）
     * @param distractorMap questionId → (optionKey → pointNodeCode) 映射（由 extractDistractorMap 提供）
     * @param courseId     课程 ID（用于过滤 kg_node）
     * @return 薄弱前置列表，按 hitCount 降序、nodeCode 升序排列
     */
    private List<PracticeSubmitVO.WeakPrerequisiteVO> aggregateWeakPrerequisites(
            List<Long> questionIds,
            List<AnswerGrader.GradeResult> gradeResults,
            Map<Long, Map<String, String>> distractorMap,
            Long courseId) {

        // 按 nodeCode 聚合命中计数
        Map<String, Integer> hitCount = new HashMap<>();
        for (int i = 0; i < questionIds.size(); i++) {
            Long questionId = questionIds.get(i);
            Set<String> wrongKeys = gradeResults.get(i).wrongChosenKeys();
            Map<String, String> optToNode = distractorMap.getOrDefault(questionId, Map.of());
            for (String wrongKey : wrongKeys) {
                String nodeCode = optToNode.get(wrongKey);
                if (nodeCode != null) {
                    hitCount.merge(nodeCode, 1, Integer::sum);
                }
            }
        }

        // 过滤低于阈值的，加载节点名称
        List<String> weakNodeCodes = hitCount.entrySet().stream()
                .filter(e -> e.getValue() >= distractorThreshold)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (weakNodeCodes.isEmpty()) {
            return List.of();
        }

        List<KgNode> nodes = kgNodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .in(KgNode::getNodeCode, weakNodeCodes));
        Map<String, String> codeToName = nodes.stream()
                .collect(Collectors.toMap(KgNode::getNodeCode, KgNode::getName, (a, b) -> a));

        return hitCount.entrySet().stream()
                .filter(e -> e.getValue() >= distractorThreshold)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(e -> {
                    PracticeSubmitVO.WeakPrerequisiteVO wp = new PracticeSubmitVO.WeakPrerequisiteVO();
                    wp.setNodeCode(e.getKey());
                    wp.setName(codeToName.getOrDefault(e.getKey(), e.getKey()));
                    wp.setHitCount(e.getValue());
                    return wp;
                })
                .collect(Collectors.toList());
    }

    /**
     * 批量加载指定题目的所有选项（一次 IN 查询，供后续 Java 层分拣）。
     */
    private List<QuestionOption> loadAllOptions(List<Long> questionIds) {
        return questionOptionMapper.selectList(
                new LambdaQueryWrapper<QuestionOption>()
                        .in(QuestionOption::getQuestionId, questionIds));
    }

    /**
     * 从选项列表中提取正确选项集合（Java 层过滤 isCorrect=1）。
     *
     * @return questionId → Set&lt;optionKey&gt;（is_correct=1 的选项）
     */
    private Map<Long, Set<String>> extractCorrectKeys(List<QuestionOption> options) {
        Map<Long, Set<String>> result = new HashMap<>();
        for (QuestionOption opt : options) {
            if (Integer.valueOf(IS_CORRECT).equals(opt.getIsCorrect())) {
                result.computeIfAbsent(opt.getQuestionId(), k -> new HashSet<>())
                      .add(opt.getOptionKey());
            }
        }
        return result;
    }

    /**
     * 从选项列表中提取 distractor 映射（Java 层过滤 pointNodeCode 非空）。
     *
     * @return questionId → (optionKey → pointNodeCode)
     */
    private Map<Long, Map<String, String>> extractDistractorMap(List<QuestionOption> options) {
        Map<Long, Map<String, String>> result = new HashMap<>();
        for (QuestionOption opt : options) {
            if (opt.getPointNodeCode() != null && !opt.getPointNodeCode().isBlank()) {
                result.computeIfAbsent(opt.getQuestionId(), k -> new HashMap<>())
                      .put(opt.getOptionKey(), opt.getPointNodeCode());
            }
        }
        return result;
    }

    // ── 自动通过判定（T5）────────────────────────────────────────────────────

    /**
     * 自动通过判定（首次提交路径）：若 session.pathItemId 非空，读当前 item 状态；
     * 已完成直接返回 true（幂等）；未完成且目标节点掌握度 ≥ masteredThreshold 则置完成。
     *
     * <p>不重复做 AccessGuard 鉴权（submit 入口已做会话归属校验）。
     *
     * @param session    练习会话
     * @param afterMastery 提交后目标节点掌握度记录
     * @return 路径步骤是否已完成
     */
    private boolean maybeCompletePathItem(PracticeSession session, StudentMastery afterMastery) {
        if (session.getPathItemId() == null) {
            return false; // 自由练习，无路径步骤
        }
        LearningPathItem item = learningPathItemMapper.selectById(session.getPathItemId());
        if (item == null) {
            return false;
        }
        if (item.getStatus() != null && item.getStatus() == ITEM_DONE) {
            return true; // 幂等：已完成不重复写
        }
        double score = scoreOf(afterMastery);
        if (score >= masteredThreshold) {
            LearningPathItem upd = new LearningPathItem();
            upd.setId(item.getId());
            upd.setStatus(ITEM_DONE);
            upd.setCompletedAt(LocalDateTime.now());
            learningPathItemMapper.updateById(upd);
            return true;
        }
        return false;
    }

    /**
     * 只读路径 item 完成状态（幂等重放路径）：不触发任何写操作。
     *
     * @param session 练习会话（pathItemId 可空）
     * @return 路径步骤是否已完成（pathItemId 为空或 item 不存在返回 false）
     */
    private boolean readItemCompleted(PracticeSession session) {
        if (session.getPathItemId() == null) {
            return false;
        }
        LearningPathItem item = learningPathItemMapper.selectById(session.getPathItemId());
        return item != null && item.getStatus() != null && item.getStatus() == ITEM_DONE;
    }

    // ── 路径重算触发（T6）────────────────────────────────────────────────────

    /**
     * 路径重算触发判定（§2.3 第 7 步）：满足以下任一条件即调 {@link PathService#generate} 重算路径：
     * <ul>
     *   <li>(a) weakPrerequisites 中存在当前掌握等级 &lt;2 的前置节点（命中次数已由 T5 过滤）；</li>
     *   <li>(b) 目标节点练后 mastery_score &lt; wenjin.mastery.weak-threshold 且该节点不在当前有效路径中。</li>
     * </ul>
     *
     * <p>重放路径（session.status=1）不调用此方法，pathRegenerated 恒 false（见 rebuildSubmittedResult）。
     *
     * @param session    练习会话（含 studentId/courseId/nodeId）
     * @param weakPreqs  T5 聚合的薄弱前置列表（已过滤 hitCount &lt; threshold 项）
     * @param after      练后目标节点掌握度记录（null=未学，视为 0 分）
     * @return 是否触发了路径重算
     */
    private boolean maybeRegeneratePath(PracticeSession session,
                                        List<PracticeSubmitVO.WeakPrerequisiteVO> weakPreqs,
                                        StudentMastery after) {
        // 条件 (a)：weakPreqs 中有掌握等级 < 2 的节点
        if (!weakPreqs.isEmpty()
                && anyWeakPreqBelowLevel2(weakPreqs, session.getStudentId(), session.getCourseId())) {
            doRegenerate(session);
            return true;
        }
        // 条件 (b)：练后掌握度 < weakThreshold 且目标节点不在当前路径中
        if (scoreOf(after) < weakThreshold
                && !nodeInActivePath(session.getStudentId(), session.getCourseId(), session.getNodeId())) {
            doRegenerate(session);
            return true;
        }
        return false;
    }

    /**
     * 执行路径重算：构造 PathGenerateRequest（targetNodeId=null，沿用诊断卡点）并调 PathService.generate。
     * 返回值不使用——PathService.generate 的副作用（旧路径失效、新路径写库）即为目标。
     */
    private void doRegenerate(PracticeSession session) {
        PathGenerateRequest req = new PathGenerateRequest();
        req.setStudentId(session.getStudentId());
        req.setCourseId(session.getCourseId());
        req.setTargetNodeId(null); // null = 沿用 DiagnosticResultService 推断的卡点
        req.setUseAi(false);
        pathService.generate(req);
    }

    /**
     * 检查 weakPreqs 中是否有任意节点当前掌握等级 &lt;2（薄弱或未学）。
     *
     * <p>流程：nodeCode → kgNodeMapper 查 nodeId → studentMasteryMapper 批量查等级；
     * 无掌握度记录的节点视为等级 0（未学），同样 &lt;2。
     *
     * @param weakPreqs 薄弱前置列表（不为空）
     * @param studentId 学生 ID
     * @param courseId  课程 ID
     * @return 是否存在等级 &lt;2 的前置节点
     */
    private boolean anyWeakPreqBelowLevel2(List<PracticeSubmitVO.WeakPrerequisiteVO> weakPreqs,
                                           Long studentId, Long courseId) {
        List<String> nodeCodes = weakPreqs.stream()
                .map(PracticeSubmitVO.WeakPrerequisiteVO::getNodeCode)
                .collect(Collectors.toList());

        List<KgNode> nodes = kgNodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .in(KgNode::getNodeCode, nodeCodes));
        if (nodes.isEmpty()) {
            return false; // 找不到节点，保守不触发
        }

        List<Long> nodeIds = nodes.stream().map(KgNode::getId).collect(Collectors.toList());

        List<StudentMastery> masteries = studentMasteryMapper.selectList(
                new LambdaQueryWrapper<StudentMastery>()
                        .eq(StudentMastery::getStudentId, studentId)
                        .eq(StudentMastery::getCourseId, courseId)
                        .in(StudentMastery::getNodeId, nodeIds));

        // 已掌握节点 ID 集合（masteryLevel >= 2）
        Set<Long> masteredIds = masteries.stream()
                .filter(m -> m.getMasteryLevel() != null && m.getMasteryLevel() >= 2)
                .map(StudentMastery::getNodeId)
                .collect(Collectors.toSet());

        // 任意节点不在已掌握集合中 → 等级 < 2
        return nodeIds.stream().anyMatch(id -> !masteredIds.contains(id));
    }

    /**
     * 检查目标节点是否在当前有效学习路径（status=1）的步骤列表中。
     *
     * @param studentId 学生 ID
     * @param courseId  课程 ID
     * @param nodeId    目标节点 ID（练习节点 = session.nodeId）
     * @return 目标节点是否在当前路径中
     */
    private boolean nodeInActivePath(Long studentId, Long courseId, Long nodeId) {
        List<LearningPath> activePaths = learningPathMapper.selectList(
                new LambdaQueryWrapper<LearningPath>()
                        .eq(LearningPath::getStudentId, studentId)
                        .eq(LearningPath::getCourseId, courseId)
                        .eq(LearningPath::getStatus, PATH_ACTIVE));
        if (activePaths.isEmpty()) {
            return false;
        }

        List<Long> pathIds = activePaths.stream().map(LearningPath::getId).collect(Collectors.toList());
        List<LearningPathItem> items = learningPathItemMapper.selectList(
                new LambdaQueryWrapper<LearningPathItem>()
                        .in(LearningPathItem::getPathId, pathIds)
                        .eq(LearningPathItem::getNodeId, nodeId));
        return !items.isEmpty();
    }

    // ── submit 通用内部方法 ──────────────────────────────────────────────────

    /** 构造单题判分 VO（analysis = 题目自带解析文本 question.analysis，题库未提供时为 null）。 */
    private PracticeSubmitVO.GradeItemVO buildGradeItem(Long questionId, int type,
                                                        AnswerGrader.GradeResult result,
                                                        Set<String> correctKeys,
                                                        String analysis) {
        PracticeSubmitVO.GradeItemVO item = new PracticeSubmitVO.GradeItemVO();
        item.setQuestionId(questionId);
        item.setCorrect(result.correct()); // 简答为 null
        item.setAnalysis(analysis);
        item.setCorrectAnswer(buildCorrectAnswer(type, correctKeys));
        return item;
    }

    /**
     * 正确答案展示串：单选/判断为单字母；多选为按字典序排序的逗号串；
     * 简答（或题库无正确项）为 null。
     */
    private String buildCorrectAnswer(int type, Set<String> correctKeys) {
        if (type == QuestionType.SHORT_ANSWER || correctKeys.isEmpty()) {
            return null;
        }
        return correctKeys.stream().sorted().collect(Collectors.joining(","));
    }

    /** 解析冻结的 question_ids 串（逗号分隔）为 ID 集合。 */
    private Set<Long> parseFrozenIds(String questionIds) {
        if (questionIds == null || questionIds.isBlank()) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String part : questionIds.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                ids.add(Long.parseLong(trimmed));
            }
        }
        return ids;
    }

    /** 批量查题目（一次 IN，题型与解析共用这次查询）：questionId → Question。 */
    private Map<Long, Question> loadQuestions(List<Long> questionIds) {
        List<Question> questions = questionMapper.selectList(
                new LambdaQueryWrapper<Question>().in(Question::getId, questionIds));
        Map<Long, Question> questionById = new HashMap<>();
        for (Question q : questions) {
            questionById.put(q.getId(), q);
        }
        return questionById;
    }

    /** 题型（题目缺失时默认单选，安全兜底，对齐诊断侧）。 */
    private int typeOf(Question question) {
        return (question == null || question.getType() == null)
                ? QuestionType.SINGLE : question.getType();
    }

    /** 题目解析文本（题目缺失或未提供解析时为 null）。 */
    private String analysisOf(Question question) {
        return question == null ? null : question.getAnalysis();
    }

    /** 查目标节点当前掌握度（无记录返回 null=未学）。 */
    private StudentMastery queryMastery(Long studentId, Long courseId, Long nodeId) {
        return studentMasteryMapper.selectOne(
                new LambdaQueryWrapper<StudentMastery>()
                        .eq(StudentMastery::getStudentId, studentId)
                        .eq(StudentMastery::getCourseId, courseId)
                        .eq(StudentMastery::getNodeId, nodeId));
    }

    /** 掌握度分值（无记录=0.0）。 */
    private Double scoreOf(StudentMastery mastery) {
        if (mastery == null || mastery.getMasteryScore() == null) {
            return 0.0;
        }
        return mastery.getMasteryScore().doubleValue();
    }

    /** 掌握等级文字：2=已掌握, 1=薄弱, 其余=未学。 */
    private String levelText(StudentMastery mastery) {
        if (mastery == null || mastery.getMasteryLevel() == null) {
            return "未学";
        }
        return switch (mastery.getMasteryLevel()) {
            case 2 -> "已掌握";
            case 1 -> "薄弱";
            default -> "未学";
        };
    }

    // ── 内部方法 ─────────────────────────────────────────────────────────────

    /**
     * 查询该节点指定权重下的 APPROVED 题，排除近期已答。
     *
     * @param nodeId        知识点 ID
     * @param weight        权重（1=主, 2=次）
     * @param recentIds     近期已答题 ID 集合（用于排除）
     * @return 可用题列表（按 DB 返回顺序，downstream 会重排）
     */
    private List<Question> fetchApprovedPool(Long nodeId, int weight, Set<Long> recentIds) {
        // 查询 question_node 关联
        List<QuestionNode> links = questionNodeMapper.selectList(
                new LambdaQueryWrapper<QuestionNode>()
                        .eq(QuestionNode::getNodeId, nodeId)
                        .eq(QuestionNode::getWeight, weight));
        if (links.isEmpty()) {
            return new ArrayList<>();
        }

        // 提取 questionId，查 question（DB 过滤 APPROVED）
        List<Long> questionIds = links.stream()
                .map(QuestionNode::getQuestionId)
                .collect(Collectors.toList());
        List<Question> approved = questionMapper.selectList(
                new LambdaQueryWrapper<Question>()
                        .in(Question::getId, questionIds)
                        .eq(Question::getStatus, QuestionStatus.APPROVED));

        // 排除近期已答
        return approved.stream()
                .filter(q -> !recentIds.contains(q.getId()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * 轻度难度分层取样：优先覆盖难度 2–4 各至少一题，随后从剩余题中补足（同样优先 2–4 范围）。
     *
     * <p>"允许简化为难度排序取样"：先按"是否在 2–4 范围内"降序，再按难度升序，再按 id 升序排列，
     * 取前 {@code size} 道；首先尝试从 2、3、4 各取一道种子题以保证覆盖，再填充剩余。</p>
     *
     * @param pool 候选题池（保证 ≥ 1 道）
     * @param size 目标题数（已夹紧）
     */
    private List<Question> stratifyPick(List<Question> pool, int size) {
        if (pool.size() <= size) {
            return new ArrayList<>(pool);
        }

        List<Question> selected = new ArrayList<>(size);
        Set<Long> selectedIds = new HashSet<>();

        // 步骤 A：从难度 2、3、4 各取 id 最小的一道作为种子（保证覆盖 2–4 范围）
        for (int targetDiff : new int[]{2, 3, 4}) {
            if (selected.size() >= size) break;
            pool.stream()
                    .filter(q -> q.getDifficulty() != null && q.getDifficulty() == targetDiff)
                    .min(Comparator.comparingLong(Question::getId))
                    .ifPresent(q -> {
                        if (selectedIds.add(q.getId())) {
                            selected.add(q);
                        }
                    });
        }

        // 步骤 B：补充剩余配额，按"2–4 优先，其他次之；同段内难度升序，再 id 升序"
        if (selected.size() < size) {
            pool.stream()
                    .filter(q -> !selectedIds.contains(q.getId()))
                    .sorted(Comparator
                            .<Question, Integer>comparing(q -> inPreferredRange(q) ? 0 : 1)
                            .thenComparingInt(q -> q.getDifficulty() == null ? 999 : q.getDifficulty())
                            .thenComparingLong(Question::getId))
                    .forEach(q -> {
                        if (selected.size() < size) {
                            selected.add(q);
                            selectedIds.add(q.getId());
                        }
                    });
        }

        return selected;
    }

    /** 难度 2–4 为"首选"范围，供排序优先级使用。 */
    private boolean inPreferredRange(Question q) {
        return q.getDifficulty() != null && q.getDifficulty() >= 2 && q.getDifficulty() <= 4;
    }

    /**
     * 将已选题列表构造为脱敏 VO（复用 {@link PaperQuestionVO} 同款结构）。
     * 选项只暴露 key + text，绝不含 isCorrect / pointNodeCode / answer。
     *
     * @param questions 已选题（保持 stratifyPick 返回顺序，与 question_ids 冻结顺序一致）
     * @param chapter   练习节点的章节（统一设为该节点所属章节）
     */
    private List<PaperQuestionVO> buildQuestionVOs(List<Question> questions, String chapter) {
        if (questions.isEmpty()) {
            return List.of();
        }
        List<Long> ids = questions.stream().map(Question::getId).collect(Collectors.toList());

        // 批量查选项（一次 IN，避免 N+1）
        List<QuestionOption> allOpts = questionOptionMapper.selectList(
                new LambdaQueryWrapper<QuestionOption>()
                        .in(QuestionOption::getQuestionId, ids));
        Map<Long, List<QuestionOption>> optsByQuestion = allOpts.stream()
                .collect(Collectors.groupingBy(QuestionOption::getQuestionId));

        return questions.stream()
                .map(q -> {
                    PaperQuestionVO vo = new PaperQuestionVO();
                    vo.setQuestionId(q.getId());
                    vo.setStem(q.getStem());
                    vo.setType(q.getType());
                    vo.setChapter(chapter); // 同节点下所有题共享节点 chapter

                    List<QuestionOption> opts = optsByQuestion.getOrDefault(q.getId(), List.of());
                    List<PaperQuestionVO.OptionVO> optVOs = opts.stream()
                            .sorted(Comparator.comparing(QuestionOption::getOptionKey))
                            .map(o -> {
                                PaperQuestionVO.OptionVO ov = new PaperQuestionVO.OptionVO();
                                ov.setKey(o.getOptionKey());
                                ov.setText(o.getOptionText());
                                // 绝不设置 isCorrect / pointNodeCode：脱敏保证
                                return ov;
                            })
                            .collect(Collectors.toList());
                    vo.setOptions(optVOs);
                    return vo;
                })
                .collect(Collectors.toList());
    }
}
