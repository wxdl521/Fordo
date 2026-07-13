package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.GradedAnswer;
import com.wenjin.dto.PaperQuestionVO;
import com.wenjin.dto.PaperVO;
import com.wenjin.dto.QuestionGradeVO;
import com.wenjin.dto.SubmitRequest;
import com.wenjin.dto.SubmitResult;
import com.wenjin.entity.AnswerRecord;
import com.wenjin.entity.DiagnosticSession;
import com.wenjin.entity.DiagnosticSessionQuestion;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.Question;
import com.wenjin.entity.QuestionNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.mapper.AnswerRecordMapper;
import com.wenjin.mapper.DiagnosticSessionMapper;
import com.wenjin.mapper.DiagnosticSessionQuestionMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionMapper;
import com.wenjin.mapper.QuestionNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.service.CourseService;
import com.wenjin.service.DiagnosticService;
import com.wenjin.service.MasteryService;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 入口诊断服务实现（T6：分层抽样组卷 + 会话冻结；T7：精确集合交卷 + 幂等结果）。
 *
 * <p>组卷时创建 {@code diagnostic_session} 与冻结题行；交卷时 {@code SELECT … FOR UPDATE}
 * 校验归属/状态/题集精确匹配，掌握度只记账一次，完成后结果写入 {@code result_json} 供重放。
 */
@Service
public class DiagnosticServiceImpl implements DiagnosticService {

    /** 主知识点权重值 */
    private static final int WEIGHT_MAIN = 1;
    /** chapter 缺失时的兜底值 */
    private static final String CHAPTER_UNKNOWN = "未分类";
    /** answer_record.is_correct 正确标记值 */
    private static final int IS_CORRECT = 1;
    /** answer_record.is_correct 错误标记值 */
    private static final int IS_WRONG = 0;
    /** answer_record.scene：诊断 */
    private static final int SCENE_DIAGNOSTIC = 1;
    /** diagnostic_session.status：进行中 */
    private static final int SESSION_IN_PROGRESS = 0;
    /** diagnostic_session.status：已完成 */
    private static final int SESSION_COMPLETED = 1;

    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final KgNodeMapper kgNodeMapper;
    private final AnswerRecordMapper answerRecordMapper;
    private final DiagnosticSessionMapper diagnosticSessionMapper;
    private final DiagnosticSessionQuestionMapper diagnosticSessionQuestionMapper;
    private final MasteryService masteryService;
    private final CourseService courseService;
    private final FeatureFlagsProperties featureFlags;
    private final ObjectMapper objectMapper;

    /** 试卷目标题数，可通过配置覆盖；未配置时默认 25。 */
    @Value("${wenjin.diagnostic.paper-size:25}")
    private int paperSize;

    public DiagnosticServiceImpl(QuestionMapper questionMapper,
                                 QuestionOptionMapper questionOptionMapper,
                                 QuestionNodeMapper questionNodeMapper,
                                 KgNodeMapper kgNodeMapper,
                                 AnswerRecordMapper answerRecordMapper,
                                 DiagnosticSessionMapper diagnosticSessionMapper,
                                 DiagnosticSessionQuestionMapper diagnosticSessionQuestionMapper,
                                 MasteryService masteryService,
                                 CourseService courseService,
                                 FeatureFlagsProperties featureFlags,
                                 ObjectMapper objectMapper) {
        this.questionMapper = questionMapper;
        this.questionOptionMapper = questionOptionMapper;
        this.questionNodeMapper = questionNodeMapper;
        this.kgNodeMapper = kgNodeMapper;
        this.answerRecordMapper = answerRecordMapper;
        this.diagnosticSessionMapper = diagnosticSessionMapper;
        this.diagnosticSessionQuestionMapper = diagnosticSessionQuestionMapper;
        this.masteryService = masteryService;
        this.courseService = courseService;
        this.featureFlags = featureFlags;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaperVO composePaper(Long courseId) {
        ensureFixedDiagnosticEnabled();

        if (paperSize <= 0) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "诊断试卷题数配置不合法: " + paperSize);
        }
        if (courseId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "courseId 不能为空");
        }

        long studentId = CurrentUser.requireStudent().userId();
        courseService.assertAccessibleByStudent(studentId, courseId);

        // ── 1. 取所有 APPROVED（status=1）题 ────────────────────────────────
        List<Question> approved = questionMapper.selectList(
                new LambdaQueryWrapper<Question>()
                        .eq(Question::getCourseId, courseId)
                        .eq(Question::getStatus, QuestionStatus.APPROVED));

        PaperVO paper = new PaperVO();
        paper.setCourseId(courseId);

        if (approved.isEmpty()) {
            paper.setTotal(0);
            paper.setQuestions(List.of());
            return paper;
        }

        // ── 2. 收集题目 ID，查主知识点（weight=1） ────────────────────────────
        List<Long> approvedIds = approved.stream().map(Question::getId).collect(Collectors.toList());

        List<QuestionNode> mainNodes = questionNodeMapper.selectList(
                new LambdaQueryWrapper<QuestionNode>()
                        .in(QuestionNode::getQuestionId, approvedIds)
                        .eq(QuestionNode::getWeight, WEIGHT_MAIN));

        // ── 3. 取课程所有图谱节点，构建 nodeId → KgNode ──────────────────────
        List<KgNode> kgNodes = kgNodeMapper.selectList(
                new LambdaQueryWrapper<KgNode>()
                        .eq(KgNode::getCourseId, courseId)
                        .isNull(KgNode::getArchivedAt));

        Map<Long, KgNode> nodeMap = new HashMap<>();
        for (KgNode n : kgNodes) {
            nodeMap.put(n.getId(), n);
        }

        // 只有至少一个主知识点仍在本课程 active 节点集中，题目才可参与组卷。
        // 多主节点时忽略已归档链接，保留遇到的第一个 active 主节点用于分章。
        Map<Long, Long> questionToNodeId = new HashMap<>();
        for (QuestionNode qn : mainNodes) {
            if (nodeMap.containsKey(qn.getNodeId())) {
                questionToNodeId.putIfAbsent(qn.getQuestionId(), qn.getNodeId());
            }
        }
        approved = approved.stream()
                .filter(q -> questionToNodeId.containsKey(q.getId()))
                .collect(Collectors.toList());
        if (approved.isEmpty()) {
            paper.setTotal(0);
            paper.setQuestions(List.of());
            return paper;
        }

        Map<Long, String> questionChapter = new HashMap<>();
        Map<Long, Boolean> questionIsKey = new HashMap<>();
        for (Question q : approved) {
            Long nodeId = questionToNodeId.get(q.getId());
            KgNode node = nodeId == null ? null : nodeMap.get(nodeId);
            String chapter = (node != null && node.getChapter() != null)
                    ? node.getChapter() : CHAPTER_UNKNOWN;
            boolean isKey = node != null && node.getIsKey() != null && node.getIsKey() == 1;
            questionChapter.put(q.getId(), chapter);
            questionIsKey.put(q.getId(), isKey);
        }

        // ── 4. 按章节分组 ────────────────────────────────────────────────────
        LinkedHashMap<String, List<Question>> byChapter = new LinkedHashMap<>();
        for (Question q : approved) {
            String ch = questionChapter.get(q.getId());
            byChapter.computeIfAbsent(ch, k -> new ArrayList<>()).add(q);
        }

        int target = Math.min(paperSize, approved.size());
        int numChapters = byChapter.size();
        List<String> chapterOrder = new ArrayList<>(byChapter.keySet());
        Map<String, Integer> quota = new LinkedHashMap<>();

        if (numChapters >= target) {
            for (int i = 0; i < target; i++) {
                quota.put(chapterOrder.get(i), 1);
            }
        } else {
            for (String ch : chapterOrder) {
                quota.put(ch, 1);
            }
            int remaining = target - numChapters;
            int total = approved.size();

            Map<String, Double> idealExtra = new LinkedHashMap<>();
            Map<String, Integer> floorExtra = new LinkedHashMap<>();
            int floorSum = 0;
            for (String ch : chapterOrder) {
                int chSize = byChapter.get(ch).size();
                double ideal = (double) chSize / total * remaining;
                idealExtra.put(ch, ideal);
                int floor = (int) ideal;
                floorExtra.put(ch, floor);
                floorSum += floor;
            }

            int extra = remaining - floorSum;
            List<String> byRemainder = new ArrayList<>(chapterOrder);
            byRemainder.sort((a, b) -> {
                double ra = idealExtra.get(a) - floorExtra.get(a);
                double rb = idealExtra.get(b) - floorExtra.get(b);
                return Double.compare(rb, ra);
            });
            for (int i = 0; i < extra; i++) {
                String ch = byRemainder.get(i);
                floorExtra.put(ch, floorExtra.get(ch) + 1);
            }

            for (String ch : chapterOrder) {
                int cap = byChapter.get(ch).size();
                int assigned = 1 + floorExtra.get(ch);
                quota.put(ch, Math.min(assigned, cap));
            }
        }

        // ── 7. 各章内按 isKey desc, id asc 取配额 ────────────────────────────
        List<Question> selected = new ArrayList<>();
        Set<Long> selectedIdSet = new LinkedHashSet<>();

        for (String ch : (numChapters >= target ? chapterOrder.subList(0, target) : chapterOrder)) {
            List<Question> pool = new ArrayList<>(byChapter.get(ch));
            pool.sort(Comparator
                    .comparingInt((Question q) -> questionIsKey.get(q.getId()) ? 0 : 1)
                    .thenComparingLong(Question::getId));
            int q = quota.getOrDefault(ch, 0);
            for (int i = 0; i < q && i < pool.size(); i++) {
                selected.add(pool.get(i));
                selectedIdSet.add(pool.get(i).getId());
            }
        }

        if (selected.size() < target) {
            List<Question> unselected = approved.stream()
                    .filter(q -> !selectedIdSet.contains(q.getId()))
                    .sorted(Comparator
                            .comparingInt((Question q) -> questionIsKey.get(q.getId()) ? 0 : 1)
                            .thenComparingLong(Question::getId))
                    .collect(Collectors.toList());
            int need = target - selected.size();
            for (int i = 0; i < need && i < unselected.size(); i++) {
                selected.add(unselected.get(i));
            }
        }

        // ── 8. 冻结会话 + 题目（ordinal 与下发顺序一致） ─────────────────────
        String token = UUID.randomUUID().toString();
        DiagnosticSession session = new DiagnosticSession();
        session.setToken(token);
        session.setStudentId(studentId);
        session.setCourseId(courseId);
        session.setStatus(SESSION_IN_PROGRESS);
        session.setCreatedAt(LocalDateTime.now());
        diagnosticSessionMapper.insert(session);

        for (int i = 0; i < selected.size(); i++) {
            DiagnosticSessionQuestion row = new DiagnosticSessionQuestion();
            row.setSessionId(session.getId());
            row.setQuestionId(selected.get(i).getId());
            row.setCourseId(courseId);
            row.setOrdinal(i);
            diagnosticSessionQuestionMapper.insert(row);
        }

        // ── 9. 构造 PaperVO（选项只暴露 key + text，不含答案/正误） ───────────
        List<Long> selectedIds = selected.stream().map(Question::getId).toList();
        List<QuestionOption> allOpts = questionOptionMapper.selectList(
                new LambdaQueryWrapper<QuestionOption>()
                        .in(QuestionOption::getQuestionId, selectedIds));
        Map<Long, List<QuestionOption>> optsByQuestion = allOpts.stream()
                .collect(Collectors.groupingBy(QuestionOption::getQuestionId));

        List<PaperQuestionVO> questionVOs = new ArrayList<>(selected.size());
        for (Question q : selected) {
            PaperQuestionVO vo = new PaperQuestionVO();
            vo.setQuestionId(q.getId());
            vo.setStem(q.getStem());
            vo.setChapter(questionChapter.get(q.getId()));
            vo.setType(q.getType());

            List<QuestionOption> opts = optsByQuestion.getOrDefault(q.getId(), List.of());
            List<PaperQuestionVO.OptionVO> optVOs = opts.stream()
                    .sorted(Comparator.comparing(QuestionOption::getOptionKey))
                    .map(o -> {
                        PaperQuestionVO.OptionVO ov = new PaperQuestionVO.OptionVO();
                        ov.setKey(o.getOptionKey());
                        ov.setText(o.getOptionText());
                        return ov;
                    })
                    .collect(Collectors.toList());
            vo.setOptions(optVOs);
            questionVOs.add(vo);
        }

        paper.setSessionToken(token);
        paper.setQuestions(questionVOs);
        paper.setTotal(questionVOs.size());
        return paper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubmitResult submit(SubmitRequest req) {
        if (req == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "提交请求不能为空");
        }
        if (req.getSessionToken() == null || req.getSessionToken().isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "缺少 sessionToken，请重新组卷");
        }

        // ── 1. 锁定会话（当前读）────────────────────────────────────────────
        DiagnosticSession session = diagnosticSessionMapper.selectByTokenForUpdate(req.getSessionToken().trim());
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "诊断会话不存在或已失效");
        }

        // ── 2. 归属：必须本人 ────────────────────────────────────────────────
        Long principalStudentId = req.getStudentId();
        if (principalStudentId == null || !principalStudentId.equals(session.getStudentId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "会话归属校验失败：不能提交非本人的诊断会话");
        }

        // ── 3. 课程一致性（若客户端仍传 courseId）───────────────────────────
        if (req.getCourseId() != null && !req.getCourseId().equals(session.getCourseId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "courseId 与会话课程不一致");
        }

        // ── 4. 已完成 → 幂等重放（不再写 answer_record / 不再 applyAnswers）──
        if (session.getStatus() != null && session.getStatus() == SESSION_COMPLETED) {
            return replayCompletedResult(session);
        }

        // 开关只阻止需要产生写入的未完成会话；已完成结果的幂等读取不受影响。
        ensureFixedDiagnosticEnabled();

        // ── 5. 加载冻结题集 ──────────────────────────────────────────────────
        List<DiagnosticSessionQuestion> frozenRows = diagnosticSessionQuestionMapper.selectList(
                new LambdaQueryWrapper<DiagnosticSessionQuestion>()
                        .eq(DiagnosticSessionQuestion::getSessionId, session.getId())
                        .orderByAsc(DiagnosticSessionQuestion::getOrdinal));
        Set<Long> frozenIds = frozenRows.stream()
                .map(DiagnosticSessionQuestion::getQuestionId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<SubmitRequest.Answer> answers = req.getAnswers();
        if (answers == null) {
            answers = List.of();
        }

        // ── 6. 精确集合匹配：无重复、无缺失、无外来题 ────────────────────────
        List<Long> answerQids = new ArrayList<>(answers.size());
        for (SubmitRequest.Answer a : answers) {
            if (a == null || a.getQuestionId() == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "作答项缺少 questionId");
            }
            answerQids.add(a.getQuestionId());
        }
        Set<Long> answerSet = new HashSet<>(answerQids);
        if (answerSet.size() != answerQids.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "作答含重复 questionId");
        }
        if (!answerSet.equals(frozenIds)) {
            // 细分错误信息便于攻击测试断言
            for (Long qid : answerSet) {
                if (!frozenIds.contains(qid)) {
                    throw new BusinessException(ResultCode.BAD_REQUEST,
                            "提交了会话外题目：questionId=" + qid);
                }
            }
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "作答题目集合与冻结试卷不一致（须全量精确匹配）");
        }

        // ── 7. 冻结题必须仍属本课且已审核（防未审核/跨课偷换）──────────────
        if (!frozenIds.isEmpty()) {
            List<Question> frozenQuestions = questionMapper.selectList(
                    new LambdaQueryWrapper<Question>().in(Question::getId, frozenIds));
            Map<Long, Question> byId = new HashMap<>();
            for (Question q : frozenQuestions) {
                byId.put(q.getId(), q);
            }
            for (Long qid : frozenIds) {
                Question q = byId.get(qid);
                if (q == null) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "冻结题目不存在：questionId=" + qid);
                }
                if (q.getCourseId() == null || !q.getCourseId().equals(session.getCourseId())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST,
                            "跨课程题目不可提交：questionId=" + qid);
                }
                if (q.getStatus() == null || q.getStatus() != QuestionStatus.APPROVED) {
                    throw new BusinessException(ResultCode.BAD_REQUEST,
                            "未审核题目不可计分：questionId=" + qid);
                }
            }
        }

        // ── 8. 抢占 CAS：status 0→1，输者重放 ────────────────────────────────
        int affected = diagnosticSessionMapper.update(null, new UpdateWrapper<DiagnosticSession>()
                .eq("id", session.getId())
                .eq("status", SESSION_IN_PROGRESS)
                .set("status", SESSION_COMPLETED));
        if (affected == 0) {
            DiagnosticSession current = diagnosticSessionMapper.selectByTokenForUpdate(session.getToken());
            if (current != null && current.getStatus() != null && current.getStatus() == SESSION_COMPLETED) {
                return replayCompletedResult(current);
            }
            throw new BusinessException(ResultCode.BAD_REQUEST, "会话状态异常，请重新组卷");
        }

        // ── 9. 判分 + 写 answer_record（unique session×question 防双写）────────
        Map<Long, String> chosenByQid = new HashMap<>();
        for (SubmitRequest.Answer a : answers) {
            chosenByQid.put(a.getQuestionId(), a.getOptionKey());
        }

        List<Long> questionIds = new ArrayList<>(frozenIds);
        Map<Long, Integer> typeByQuestion = new HashMap<>();
        if (!questionIds.isEmpty()) {
            List<Question> questionList = questionMapper.selectList(
                    new LambdaQueryWrapper<Question>().in(Question::getId, questionIds));
            for (Question q : questionList) {
                typeByQuestion.put(q.getId(), q.getType());
            }
        }

        Map<Long, Set<String>> correctKeysByQuestion = new HashMap<>();
        Map<Long, String> firstCorrectKeyByQuestion = new HashMap<>();
        if (!questionIds.isEmpty()) {
            List<QuestionOption> correctOptions = questionOptionMapper.selectList(
                    new LambdaQueryWrapper<QuestionOption>()
                            .in(QuestionOption::getQuestionId, questionIds)
                            .eq(QuestionOption::getIsCorrect, IS_CORRECT));
            for (QuestionOption opt : correctOptions) {
                correctKeysByQuestion
                        .computeIfAbsent(opt.getQuestionId(), k -> new HashSet<>())
                        .add(opt.getOptionKey());
                firstCorrectKeyByQuestion.putIfAbsent(opt.getQuestionId(), opt.getOptionKey());
            }
        }

        AnswerGrader grader = new AnswerGrader();
        List<QuestionGradeVO> grades = new ArrayList<>(questionIds.size());
        List<GradedAnswer> gradedAnswers = new ArrayList<>(questionIds.size());
        int correctCount = 0;
        LocalDateTime now = LocalDateTime.now();

        // 按冻结 ordinal 顺序判分，保证 grades 稳定
        for (DiagnosticSessionQuestion row : frozenRows) {
            Long questionId = row.getQuestionId();
            String chosen = chosenByQid.get(questionId);
            int type = typeByQuestion.getOrDefault(questionId, QuestionType.SINGLE);
            Set<String> correctKeys = correctKeysByQuestion.getOrDefault(questionId, Set.of());
            String firstCorrectKey = firstCorrectKeyByQuestion.get(questionId);

            AnswerGrader.GradeResult gradeResult = grader.grade(type, chosen, correctKeys);
            boolean isCorrect = Boolean.TRUE.equals(gradeResult.correct());

            AnswerRecord record = new AnswerRecord();
            record.setStudentId(session.getStudentId());
            record.setCourseId(session.getCourseId());
            record.setQuestionId(questionId);
            record.setStudentAnswer(chosen);
            record.setIsCorrect(isCorrect ? IS_CORRECT : IS_WRONG);
            record.setAnsweredAt(now);
            record.setScene(SCENE_DIAGNOSTIC);
            record.setFixedDiagnosticSessionId(session.getId());
            answerRecordMapper.insert(record);

            QuestionGradeVO grade = new QuestionGradeVO();
            grade.setQuestionId(questionId);
            grade.setCorrect(isCorrect);
            grade.setCorrectKey(firstCorrectKey);
            grades.add(grade);

            if (gradeResult.isGradeable()) {
                gradedAnswers.add(new GradedAnswer(questionId, isCorrect));
                if (isCorrect) {
                    correctCount++;
                }
            }
        }

        // ── 10. 掌握度仅记账一次 ─────────────────────────────────────────────
        masteryService.applyAnswers(session.getStudentId(), session.getCourseId(), gradedAnswers);

        SubmitResult result = new SubmitResult();
        result.setTotal(frozenRows.size());
        result.setCorrectCount(correctCount);
        result.setGrades(grades);

        // ── 11. 落 result_json + finished_at（status 已由 CAS 置 1）───────────
        String json = writeResultJson(result);
        diagnosticSessionMapper.update(null, new UpdateWrapper<DiagnosticSession>()
                .eq("id", session.getId())
                .set("result_json", json)
                .set("finished_at", now));

        return result;
    }

    private SubmitResult replayCompletedResult(DiagnosticSession session) {
        if (session.getResultJson() != null && !session.getResultJson().isBlank()) {
            try {
                return objectMapper.readValue(session.getResultJson(), SubmitResult.class);
            } catch (JsonProcessingException e) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "会话结果反序列化失败");
            }
        }
        // 兜底：从 answer_record 重建（result_json 尚未写入的极窄窗口 / 历史异常）
        List<AnswerRecord> records = answerRecordMapper.selectList(
                new LambdaQueryWrapper<AnswerRecord>()
                        .eq(AnswerRecord::getFixedDiagnosticSessionId, session.getId())
                        .orderByAsc(AnswerRecord::getId));
        SubmitResult result = new SubmitResult();
        result.setTotal(records.size());
        int correct = 0;
        List<QuestionGradeVO> grades = new ArrayList<>(records.size());
        for (AnswerRecord r : records) {
            QuestionGradeVO g = new QuestionGradeVO();
            g.setQuestionId(r.getQuestionId());
            boolean ok = r.getIsCorrect() != null && r.getIsCorrect() == IS_CORRECT;
            g.setCorrect(ok);
            if (ok) {
                correct++;
            }
            grades.add(g);
        }
        result.setCorrectCount(correct);
        result.setGrades(grades);
        return result;
    }

    private String writeResultJson(SubmitResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "会话结果序列化失败");
        }
    }

    private void ensureFixedDiagnosticEnabled() {
        if (featureFlags == null || !featureFlags.isFixedDiagnosticEnabled()) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "固定诊断功能暂未开放");
        }
    }
}
