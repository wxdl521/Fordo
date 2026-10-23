package com.wenjin.service.teacher;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.dto.MistakeReport;
import com.wenjin.dto.MistakeVO;
import com.wenjin.dto.PlanVO;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.QuestionOption;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.QuestionOptionMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.mapper.TeacherAnalyticsMapper;
import com.wenjin.mapper.TeacherDashboardMapper;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.MistakeQueryService;
import com.wenjin.service.plan.PlanService;
import com.wenjin.service.teacher.TeacherAnalyticsModels.*;
import com.wenjin.service.teacher.TeacherAnalyticsRows.AnswerChoiceRow;
import com.wenjin.service.teacher.TeacherAnalyticsRows.DailyAnswerRow;
import com.wenjin.service.teacher.TeacherAnalyticsRows.DailyCompanionRow;
import com.wenjin.service.teacher.TeacherAnalyticsRows.QuestionErrorRow;
import com.wenjin.service.teacher.TeacherAnalyticsRows.RosterRow;
import com.wenjin.service.teacher.TeacherAnalyticsRows.StudentActivityRow;
import com.wenjin.service.teacher.TeacherAttentionScorer.Facts;
import com.wenjin.service.teacher.TeacherAttentionScorer.Scored;
import com.wenjin.support.multiagent.ReportAggregations;
import com.wenjin.support.plan.PlanStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import static com.wenjin.service.teacher.TeacherAnalyticsModels.STATUS_AMBIGUOUS;
import static com.wenjin.service.teacher.TeacherAnalyticsModels.STATUS_NOT_FOUND;
import static com.wenjin.service.teacher.TeacherAnalyticsModels.STATUS_OK;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 教师助教的学情读服务（纯读）：全部以课程选课花名册为口径，学生范围 = {@code student_course}。
 * <p>
 * 隐私：只用学习数据（掌握度、作答、练习、复习、计划进度、伴侣会话<b>计数</b>）；
 * 绝不读取 {@code student_memory} 或伴侣对话正文。授权由调用方在请求线程完成（本服务不做鉴权）。
 * <p>
 * 效率：每个方法 O(1) 条 SQL（花名册 / 掌握行 / 作答汇总 / 快照各一条），在内存里按学生分组，无逐学生查库循环
 * （单个学生画像除外：错题 + 计划各一次调用）。
 */
@Service
public class TeacherAnalyticsService {

    /** 名单类结果的行数上限（A1 引入 wenjin.limits.teacher-assistant.list-max-rows 后统一）。 */
    public static final int LIST_MAX_ROWS = 20;
    public static final int QUESTION_STATS_MAX = 10;
    /** 题目错误率排名的最小样本量。 */
    public static final int MIN_QUESTION_SAMPLE = 5;
    public static final int QUESTION_WINDOW_DAYS = 30;
    public static final int WEAKEST_NODES_CLASS = ReportAggregations.DEFAULT_WEAK_TOP_N;
    public static final int WEAKEST_NODES_STUDENT = 5;
    public static final int PROFILE_MISTAKES = 5;
    static final int MAX_CANDIDATES = 8;
    static final int MAX_CHAPTERS = 30;
    static final int DISTRACTORS_PER_QUESTION = 4;
    static final int STEM_CHARS = 40;
    static final int OPTION_TEXT_CHARS = 30;
    static final String NO_CHAPTER = "未分章";

    private static final java.time.format.DateTimeFormatter DAY = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
    private static final java.time.format.DateTimeFormatter TIME =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TeacherAnalyticsMapper analyticsMapper;
    private final TeacherDashboardMapper dashboardMapper;
    private final KgNodeMapper nodeMapper;
    private final StudentMasteryMapper masteryMapper;
    private final QuestionOptionMapper optionMapper;
    private final MasteryDecayCalculator decay;
    private final MistakeQueryService mistakeQueryService;
    private final PlanService planService;
    private final Clock clock;

    /** 可选：wenjin.limits.teacher-assistant.list-max-rows（≤ {@link #LIST_MAX_ROWS} 硬上限）。 */
    private com.wenjin.config.LimitsProperties limits;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLimits(com.wenjin.config.LimitsProperties limits) {
        this.limits = limits;
    }

    /** 名单行数上限：配置值与硬上限取小，配置缺省时用硬上限。 */
    int rowCap() {
        if (limits == null || limits.getTeacherAssistant() == null) {
            return LIST_MAX_ROWS;
        }
        return Math.max(1, Math.min(LIST_MAX_ROWS, limits.getTeacherAssistant().getListMaxRows()));
    }

    public TeacherAnalyticsService(TeacherAnalyticsMapper analyticsMapper,
                                   TeacherDashboardMapper dashboardMapper,
                                   KgNodeMapper nodeMapper,
                                   StudentMasteryMapper masteryMapper,
                                   QuestionOptionMapper optionMapper,
                                   MasteryDecayCalculator decay,
                                   MistakeQueryService mistakeQueryService,
                                   PlanService planService,
                                   Clock clock) {
        this.analyticsMapper = Objects.requireNonNull(analyticsMapper, "analyticsMapper");
        this.dashboardMapper = Objects.requireNonNull(dashboardMapper, "dashboardMapper");
        this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
        this.masteryMapper = Objects.requireNonNull(masteryMapper, "masteryMapper");
        this.optionMapper = Objects.requireNonNull(optionMapper, "optionMapper");
        this.decay = Objects.requireNonNull(decay, "decay");
        this.mistakeQueryService = Objects.requireNonNull(mistakeQueryService, "mistakeQueryService");
        this.planService = Objects.requireNonNull(planService, "planService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ───────────────────────── 1. 全班概览 ─────────────────────────

    public ClassOverview classOverview(long courseId) {
        List<RosterRow> roster = nullSafe(analyticsMapper.selectRoster(courseId));
        if (roster.isEmpty()) {
            return new ClassOverview(0, 0, null, new StudentDistribution(0, 0, 0, 0),
                    List.of(), List.of(), 0, "该课程暂无选课学生");
        }
        Map<Long, KgNode> nodes = activeNodes(courseId);
        Set<Long> rosterIds = rosterIds(roster);
        List<Cell> cells = cells(dashboardMapper.selectEnrolledMastery(courseId), nodes, rosterIds);
        Map<Long, List<Cell>> byStudent = groupByStudent(cells);

        int mastered = 0;
        int weak = 0;
        int notLearned = 0;
        int undiagnosed = 0;
        double avgSum = 0;
        int diagnosed = 0;
        for (RosterRow r : roster) {
            List<Cell> own = byStudent.get(r.getStudentId());
            if (own == null || own.isEmpty()) {
                undiagnosed++;
                continue;
            }
            diagnosed++;
            double avg = avgOf(own);
            avgSum += avg;
            switch (decay.effectiveLevelOf(avg)) {
                case 2 -> mastered++;
                case 1 -> weak++;
                default -> notLearned++;
            }
        }

        List<ReportAggregations.NodeStudentEffective> rows = new ArrayList<>();
        for (Cell c : cells) {
            rows.add(new ReportAggregations.NodeStudentEffective(c.studentId(), c.node().getNodeCode(), c.effective() / 100.0));
        }
        Map<String, KgNode> byCode = new HashMap<>();
        nodes.values().forEach(n -> byCode.put(n.getNodeCode(), n));
        List<WeakNodeView> weakest = new ArrayList<>();
        for (ReportAggregations.WeakNode w : ReportAggregations.weakNodes(rows, WEAKEST_NODES_CLASS)) {
            KgNode n = byCode.get(w.nodeCode());
            weakest.add(new WeakNodeView(w.nodeCode(), n == null ? w.nodeCode() : n.getName(),
                    n == null ? null : n.getChapter(), round1(w.avgEffective() * 100), w.sampleSize()));
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, StudentActivityRow> activity = activityByStudent(courseId, now);
        int active7d = (int) activity.values().stream().filter(a -> nz(a.getAnswers7()) > 0).count();

        String note = diagnosed == 0 ? "尚无学生产生掌握度数据（未诊断/未练习）" : null;
        return new ClassOverview(roster.size(), diagnosed,
                diagnosed == 0 ? null : round1(avgSum / diagnosed),
                new StudentDistribution(mastered, weak, notLearned, undiagnosed),
                weakest, chapterViews(nodes.values(), cells), active7d, note);
    }

    // ───────────────────────── 2. 需要关注的学生 ─────────────────────────

    public AttentionList studentsNeedingAttention(long courseId, int limit, String reason) {
        int lim = Math.max(1, Math.min(rowCap(), limit));
        String reasonFilter = StringUtils.hasText(reason) ? reason.trim().toUpperCase(Locale.ROOT) : null;
        if (reasonFilter != null && !Reason.ALL.contains(reasonFilter)) {
            throw new IllegalArgumentException("unknown reason: " + reason);
        }
        List<RosterRow> roster = nullSafe(analyticsMapper.selectRoster(courseId));
        if (roster.isEmpty()) {
            return new AttentionList(0, 0, List.of(), "该课程暂无选课学生");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, KgNode> nodes = activeNodes(courseId);
        Set<Long> rosterIds = rosterIds(roster);
        List<Cell> cells = cells(dashboardMapper.selectEnrolledMastery(courseId), nodes, rosterIds);
        Map<Long, List<Cell>> byStudent = groupByStudent(cells);
        Map<Long, StudentActivityRow> activity = activityByStudent(courseId, now);
        Map<Long, DropInfo> drops = sevenDayDrops(courseId, now, cells, nodes);
        double mastered = decay.getMasteredThreshold();

        List<Scored> scored = new ArrayList<>();
        for (RosterRow r : roster) {
            long sid = r.getStudentId();
            List<Cell> own = byStudent.getOrDefault(sid, List.of());
            StudentActivityRow act = activity.get(sid);
            DropInfo drop = drops.get(sid);
            Facts facts = new Facts(sid, displayName(r),
                    own.isEmpty() ? null : round1(avgOf(own)),
                    drop == null ? 0 : drop.drop(),
                    drop == null ? null : drop.nodeName(),
                    daysSince(act == null ? null : act.getLastAnswerAt(), now),
                    act == null || act.getLastAnswerAt() == null ? null : DAY.format(act.getLastAnswerAt()),
                    act == null ? 0 : nz(act.getWrong7()),
                    backlogOf(own, mastered));
            scored.add(TeacherAttentionScorer.score(facts, mastered, decay.getWeakThreshold()));
        }
        List<Scored> flagged = scored.stream()
                .filter(Scored::flagged)
                .filter(s -> reasonFilter == null || s.codes().contains(reasonFilter))
                .sorted(TeacherAttentionScorer.RANKING)
                .toList();
        List<AttentionItem> items = flagged.stream().limit(lim).map(s -> new AttentionItem(
                s.facts().studentId(), s.facts().name(), s.facts().avgEffective(), s.score(),
                s.codes(), s.reasons(), s.facts().lastPracticeAt(), s.facts().daysSincePractice())).toList();
        String note;
        if (flagged.isEmpty()) {
            note = reasonFilter == null ? "暂无需要特别关注的学生" : "没有学生命中该原因";
        } else if (flagged.size() > items.size()) {
            note = "共 " + flagged.size() + " 人需关注，仅列前 " + items.size() + " 人";
        } else {
            note = null;
        }
        return new AttentionList(roster.size(), flagged.size(), items, note);
    }

    // ───────────────────────── 3. 单个学生画像 ─────────────────────────

    /** @param studentRef 姓名（模糊）或学生 id；同名多人返回候选。 */
    public StudentProfile studentProfile(long courseId, String studentRef) {
        List<RosterRow> roster = nullSafe(analyticsMapper.selectRoster(courseId));
        if (roster.isEmpty()) {
            return StudentProfile.notFound("该课程暂无选课学生");
        }
        Resolution<RosterRow> res = resolveStudent(roster, studentRef);
        if (res.matches().isEmpty()) {
            return StudentProfile.notFound("本课程选课学生中没有匹配「" + clip(studentRef, 20) + "」的人");
        }
        if (res.matches().size() > 1) {
            List<StudentRef> candidates = res.matches().stream().limit(MAX_CANDIDATES)
                    .map(r -> new StudentRef(r.getStudentId(), displayName(r))).toList();
            return StudentProfile.ambiguous(candidates, "有多名学生匹配，请用学生 id 或更完整的姓名再查询");
        }
        RosterRow row = res.matches().get(0);
        long sid = row.getStudentId();
        LocalDateTime now = LocalDateTime.now(clock);

        Map<Long, KgNode> nodes = activeNodes(courseId);
        List<StudentMastery> masteries = nullSafe(masteryMapper.selectList(new LambdaQueryWrapper<StudentMastery>()
                .eq(StudentMastery::getStudentId, sid)
                .eq(StudentMastery::getCourseId, courseId)));
        List<Cell> cells = cells(masteries, nodes, Set.of(sid));

        StudentActivityRow act = activityByStudent(courseId, now).get(sid);
        PracticeWindow practice = practiceWindow(act);

        MistakeReport report = mistakeQueryService.report(sid, courseId, null, null, PROFILE_MISTAKES);
        List<MistakeView> mistakes = new ArrayList<>();
        for (MistakeVO m : report.mistakes()) {
            mistakes.add(new MistakeView(m.questionId(), nodeRef(m.primaryNode()),
                    m.misconceptionNodes() == null ? List.of()
                            : m.misconceptionNodes().stream().map(TeacherAnalyticsService::nodeRef).toList(),
                    m.answeredAt(), m.scene()));
        }
        List<NodeRef> topMisc = report.topMisconceptions().stream()
                .map(s -> new NodeRef(s.nodeCode(), s.name())).toList();

        List<StudentNodeView> weakest = cells.stream()
                .sorted(Comparator.comparingDouble(Cell::effective).thenComparing(c -> c.node().getNodeCode()))
                .limit(WEAKEST_NODES_STUDENT)
                .map(c -> new StudentNodeView(c.node().getNodeCode(), c.node().getName(), c.node().getChapter(),
                        round1(c.effective())))
                .toList();

        return new StudentProfile(STATUS_OK, new StudentRef(sid, displayName(row)),
                cells.isEmpty() ? null : round1(avgOf(cells)), cells.size(), nodes.size(),
                chapterViews(nodes.values(), cells), weakest, practice, mistakes, topMisc,
                backlogOf(cells, decay.getMasteredThreshold()), planView(sid, courseId), null,
                cells.isEmpty() ? "该学生尚无掌握度数据（未诊断/未练习）" : null);
    }

    private PlanView planView(long studentId, long courseId) {
        PlanVO plan;
        try {
            plan = planService.current(studentId, courseId);
        } catch (RuntimeException e) {
            return null;
        }
        if (plan == null || plan.getId() == null) {
            return null;
        }
        String status = plan.getStatus() == null ? null : switch (plan.getStatus()) {
            case PlanStatus.DRAFT -> "DRAFT";
            case PlanStatus.ACTIVE -> "ACTIVE";
            case PlanStatus.DONE -> "DONE";
            case PlanStatus.CANCELLED -> "CANCELLED";
            default -> String.valueOf(plan.getStatus());
        };
        return new PlanView(status, plan.getTaskCount(), plan.getDoneCount(), plan.isAtRisk(),
                plan.getTargetDate() == null ? null : plan.getTargetDate().toString());
    }

    private PracticeWindow practiceWindow(StudentActivityRow act) {
        int attempts = act == null ? 0 : nz(act.getAnswers14());
        int correct = act == null ? 0 : nz(act.getCorrect14());
        Double acc = attempts == 0 ? null : round1(100.0 * correct / attempts);
        String last = act == null || act.getLastAnswerAt() == null ? null : TIME.format(act.getLastAnswerAt());
        return new PracticeWindow(14, attempts, correct, acc, last);
    }

    // ───────────────────────── 4. 单个知识点掌握 ─────────────────────────

    public NodeMasteryResult nodeMastery(long courseId, String nodeRef) {
        Map<Long, KgNode> nodes = activeNodes(courseId);
        Resolution<KgNode> res = resolveNode(nodes.values(), nodeRef);
        if (res.matches().isEmpty()) {
            return NodeMasteryResult.notFound("本课程没有匹配「" + clip(nodeRef, 30) + "」的知识点");
        }
        if (res.matches().size() > 1) {
            return NodeMasteryResult.ambiguous(res.matches().stream().limit(MAX_CANDIDATES)
                    .map(n -> new NodeRef(n.getNodeCode(), n.getName())).toList(), "有多个知识点匹配，请指定 nodeCode");
        }
        KgNode node = res.matches().get(0);
        List<RosterRow> roster = nullSafe(analyticsMapper.selectRoster(courseId));
        Map<Long, Double> eff = new HashMap<>();
        for (StudentMastery m : nullSafe(dashboardMapper.selectEnrolledMastery(courseId))) {
            if (Objects.equals(m.getNodeId(), node.getId()) && m.getMasteryScore() != null) {
                eff.put(m.getStudentId(), decay.effectiveScoreOf(m));
            }
        }
        int mastered = 0;
        int weak = 0;
        int notLearned = 0;
        int undiagnosed = 0;
        double sum = 0;
        List<NodeStudentView> notMastered = new ArrayList<>();
        for (RosterRow r : roster) {
            Double e = eff.get(r.getStudentId());
            if (e == null) {
                undiagnosed++;
                notMastered.add(new NodeStudentView(r.getStudentId(), displayName(r), null, "未诊断"));
                continue;
            }
            sum += e;
            int level = decay.effectiveLevelOf(e);
            if (level == 2) {
                mastered++;
                continue;
            }
            if (level == 1) {
                weak++;
            } else {
                notLearned++;
            }
            notMastered.add(new NodeStudentView(r.getStudentId(), displayName(r), round1(e), level == 1 ? "薄弱" : "未学"));
        }
        notMastered.sort(Comparator
                .comparing((NodeStudentView v) -> v.effective() == null)
                .thenComparing(v -> v.effective() == null ? 0 : v.effective())
                .thenComparingLong(NodeStudentView::studentId));
        int diagnosed = mastered + weak + notLearned;
        List<NodeStudentView> shown = notMastered.stream().limit(rowCap()).toList();
        return new NodeMasteryResult(STATUS_OK, new NodeRef(node.getNodeCode(), node.getName()), node.getChapter(),
                roster.size(), new StudentDistribution(mastered, weak, notLearned, undiagnosed),
                diagnosed == 0 ? null : round1(sum / diagnosed), notMastered.size(), shown,
                null, roster.isEmpty() ? "该课程暂无选课学生"
                : (notMastered.size() > shown.size() ? "未掌握共 " + notMastered.size() + " 人，仅列前 " + shown.size() + " 人" : null));
    }

    // ───────────────────────── 5. 题目错误率与干扰项 ─────────────────────────

    public QuestionErrorStats questionErrorStats(long courseId, String nodeCode, int limit) {
        int lim = Math.max(1, Math.min(QUESTION_STATS_MAX, limit));
        Map<Long, KgNode> nodes = activeNodes(courseId);
        String nodeFilter = null;
        if (StringUtils.hasText(nodeCode)) {
            Resolution<KgNode> res = resolveNode(nodes.values(), nodeCode);
            if (res.matches().size() != 1) {
                return new QuestionErrorStats(QUESTION_WINDOW_DAYS, MIN_QUESTION_SAMPLE, List.of(),
                        res.matches().isEmpty() ? "本课程没有匹配「" + clip(nodeCode, 30) + "」的知识点"
                                : "有多个知识点匹配，请指定准确的 nodeCode");
            }
            nodeFilter = res.matches().get(0).getNodeCode();
        }
        LocalDateTime since = LocalDateTime.now(clock).minusDays(QUESTION_WINDOW_DAYS);
        List<QuestionErrorRow> rows = nullSafe(analyticsMapper.selectQuestionErrorStats(
                courseId, since, nodeFilter, MIN_QUESTION_SAMPLE, lim)).stream()
                .filter(r -> nz(r.getAttempts()) >= MIN_QUESTION_SAMPLE && nz(r.getWrongCount()) > 0)
                .sorted(Comparator
                        .comparingDouble((QuestionErrorRow r) -> -(double) nz(r.getWrongCount()) / nz(r.getAttempts()))
                        .thenComparing(r -> -nz(r.getAttempts()))
                        .thenComparing(QuestionErrorRow::getQuestionId))
                .limit(lim)
                .toList();
        if (rows.isEmpty()) {
            return new QuestionErrorStats(QUESTION_WINDOW_DAYS, MIN_QUESTION_SAMPLE, List.of(),
                    "近 " + QUESTION_WINDOW_DAYS + " 天没有作答样本 ≥ " + MIN_QUESTION_SAMPLE + " 且有错误的客观题");
        }
        Set<Long> qids = new LinkedHashSet<>();
        rows.forEach(r -> qids.add(r.getQuestionId()));
        Map<Long, Map<String, Integer>> chosen = new HashMap<>();
        for (AnswerChoiceRow c : nullSafe(analyticsMapper.selectAnswerChoices(courseId, since, qids))) {
            Map<String, Integer> per = chosen.computeIfAbsent(c.getQuestionId(), k -> new HashMap<>());
            for (String key : parseKeys(c.getStudentAnswer())) {
                per.merge(key, nz(c.getCnt()), Integer::sum);
            }
        }
        Map<Long, List<QuestionOption>> optionsByQuestion = new HashMap<>();
        for (QuestionOption o : nullSafe(optionMapper.selectList(
                new LambdaQueryWrapper<QuestionOption>().in(QuestionOption::getQuestionId, qids)))) {
            optionsByQuestion.computeIfAbsent(o.getQuestionId(), k -> new ArrayList<>()).add(o);
        }
        Map<String, KgNode> byCode = new HashMap<>();
        nodes.values().forEach(n -> byCode.put(n.getNodeCode(), n));

        List<QuestionStat> stats = new ArrayList<>();
        for (QuestionErrorRow r : rows) {
            int attempts = nz(r.getAttempts());
            int wrong = nz(r.getWrongCount());
            Map<String, Integer> counts = chosen.getOrDefault(r.getQuestionId(), Map.of());
            List<DistractorShare> dist = new ArrayList<>();
            for (QuestionOption o : optionsByQuestion.getOrDefault(r.getQuestionId(), List.of())) {
                if (o.getIsCorrect() != null && o.getIsCorrect() == 1) {
                    continue;
                }
                String key = normalizeKey(o.getOptionKey());
                int cnt = counts.getOrDefault(key, 0);
                if (cnt <= 0) {
                    continue;
                }
                KgNode mis = StringUtils.hasText(o.getPointNodeCode()) ? byCode.get(o.getPointNodeCode().trim()) : null;
                dist.add(new DistractorShare(key, clip(o.getOptionText(), OPTION_TEXT_CHARS), cnt,
                        round1(100.0 * cnt / attempts),
                        mis == null ? null : new NodeRef(mis.getNodeCode(), mis.getName())));
            }
            dist.sort(Comparator.comparingInt(DistractorShare::count).reversed().thenComparing(DistractorShare::key));
            stats.add(new QuestionStat(r.getQuestionId(), clip(r.getStem(), STEM_CHARS),
                    nz(r.getType()),
                    r.getNodeCode() == null ? null : new NodeRef(r.getNodeCode(), r.getNodeName()),
                    attempts, wrong, round1(100.0 * wrong / attempts),
                    dist.stream().limit(DISTRACTORS_PER_QUESTION).toList()));
        }
        return new QuestionErrorStats(QUESTION_WINDOW_DAYS, MIN_QUESTION_SAMPLE, stats, null);
    }

    // ───────────────────────── 6. 活跃趋势 ─────────────────────────

    public ActivityTrend activityTrend(long courseId, int days) {
        int d = Math.max(7, Math.min(30, days));
        LocalDate today = LocalDate.now(clock);
        LocalDate start = today.minusDays(d - 1L);
        LocalDateTime since = start.atStartOfDay();
        Map<String, DailyAnswerRow> answers = new HashMap<>();
        for (DailyAnswerRow r : nullSafe(analyticsMapper.selectDailyAnswers(courseId, since))) {
            answers.put(r.getDay(), r);
        }
        Map<String, Integer> companion = new HashMap<>();
        for (DailyCompanionRow r : nullSafe(analyticsMapper.selectDailyCompanion(courseId, since))) {
            companion.put(r.getDay(), nz(r.getConversations()));
        }
        List<DayStat> daily = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < d; i++) {
            String key = start.plusDays(i).format(DAY);
            DailyAnswerRow a = answers.get(key);
            int n = a == null ? 0 : nz(a.getAnswers());
            total += n;
            daily.add(new DayStat(key, n, a == null ? 0 : nz(a.getActiveStudents()), companion.getOrDefault(key, 0)));
        }
        int enrolled = dashboardMapper.countEnrolled(courseId);
        boolean companionAny = companion.values().stream().anyMatch(v -> v > 0);
        String note = enrolled == 0 ? "该课程暂无选课学生"
                : (total == 0 && !companionAny ? "统计窗口内没有任何作答或伴侣对话" : null);
        return new ActivityTrend(d, enrolled, daily, total, note);
    }

    // ───────────────────────── 7. 周报输入（M11 TeacherInsight） ─────────────────────────

    /**
     * 周报聚合输入：只含活跃选课学生（已排除教师/管理员/禁用账号）与未归档节点；effective 为 0–1 口径。
     *
     * @param rosterSize   选课学生数
     * @param masteryRows  学生×节点当前有效掌握度
     * @param deltas       7 天前→当前有效掌握度
     * @param practices    每名学生最近一次作答日（null=从未）
     * @param nodeNames    nodeCode → 节点名
     * @param studentNames studentId → 展示名（清洗后的 real_name，空则脱敏用户名）
     */
    public record WeeklyFacts(int rosterSize,
                              List<ReportAggregations.NodeStudentEffective> masteryRows,
                              List<ReportAggregations.EffectiveDelta> deltas,
                              List<ReportAggregations.StudentLastPractice> practices,
                              Map<String, String> nodeNames,
                              Map<Long, String> studentNames) {
        public static WeeklyFacts empty() {
            return new WeeklyFacts(0, List.of(), List.of(), List.of(), Map.of(), Map.of());
        }
    }

    /** 窗口 [from, to) 内的参与度计数。 */
    public record WindowEngagement(int answers, int activeStudents, int companionConversations) {
    }

    /** 周报用事实快照（只读；不读学生记忆与对话正文）。 */
    public WeeklyFacts weeklyFacts(long courseId, LocalDateTime now) {
        List<RosterRow> roster = nullSafe(analyticsMapper.selectRoster(courseId));
        if (roster.isEmpty()) {
            return WeeklyFacts.empty();
        }
        Map<Long, KgNode> nodes = activeNodes(courseId);
        List<Cell> cells = cells(dashboardMapper.selectEnrolledMastery(courseId), nodes, rosterIds(roster));

        List<ReportAggregations.NodeStudentEffective> rows = new ArrayList<>();
        Map<String, String> nodeNames = new LinkedHashMap<>();
        for (Cell c : cells) {
            rows.add(new ReportAggregations.NodeStudentEffective(c.studentId(), c.node().getNodeCode(),
                    c.effective() / 100.0));
            nodeNames.put(c.node().getNodeCode(), c.node().getName());
        }
        List<ReportAggregations.EffectiveDelta> deltas = sevenDayDeltas(courseId, now, cells, nodeNames);

        Map<Long, StudentActivityRow> activity = activityByStudent(courseId, now);
        Map<Long, String> names = new LinkedHashMap<>();
        List<ReportAggregations.StudentLastPractice> practices = new ArrayList<>();
        for (RosterRow r : roster) {
            names.put(r.getStudentId(), displayName(r));
            StudentActivityRow a = activity.get(r.getStudentId());
            practices.add(new ReportAggregations.StudentLastPractice(r.getStudentId(),
                    a == null || a.getLastAnswerAt() == null ? null : a.getLastAnswerAt().toLocalDate()));
        }
        return new WeeklyFacts(roster.size(), rows, deltas, practices, nodeNames, names);
    }

    /** 窗口 [from, to) 的参与度（周报本周/上周环比各调一次）。 */
    public WindowEngagement windowEngagement(long courseId, LocalDateTime from, LocalDateTime to) {
        TeacherAnalyticsRows.WindowAnswerRow a = analyticsMapper.selectWindowAnswers(courseId, from, to);
        Integer conv = analyticsMapper.selectWindowCompanion(courseId, from, to);
        return new WindowEngagement(a == null ? 0 : nz(a.getAnswers()),
                a == null ? 0 : nz(a.getActiveStudents()), conv == null ? 0 : conv);
    }

    // ───────────────────────── helpers: 名字解析 / 脱敏 ─────────────────────────

    record Resolution<T>(List<T> matches) {
    }

    static final int DISPLAY_NAME_MAX = 20;

    /**
     * 展示名：real_name，空则脱敏用户名。real_name 来自公开注册，属不可信输入：去控制字符/换行、折叠空白、
     * 去开头的 = + @（防公式/提示注入的纵深防御）、截断到 20 个字符。
     */
    public static String displayName(RosterRow r) {
        if (r != null) {
            String cleaned = sanitizeName(r.getRealName());
            if (!cleaned.isEmpty()) {
                return cleaned;
            }
        }
        return maskUsername(r == null ? null : r.getUsername());
    }

    static String sanitizeName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("[\\p{Cntrl}\\p{Cf}\\s]+", " ").trim();
        s = s.replaceAll("^[=+@\\s]+", "");
        if (s.codePointCount(0, s.length()) > DISPLAY_NAME_MAX) {
            s = s.substring(0, s.offsetByCodePoints(0, DISPLAY_NAME_MAX)) + "…";
        }
        return s;
    }

    static String maskUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return "学生";
        }
        String u = username.trim();
        int len = u.codePointCount(0, u.length());
        if (len <= 1) {
            return u + "*";
        }
        int first = u.offsetByCodePoints(0, 1);
        if (len == 2) {
            return u.substring(0, first) + "*";
        }
        int last = u.offsetByCodePoints(0, len - 1);
        return u.substring(0, first) + "***" + u.substring(last);
    }

    /**
     * 精确 id → 精确姓名/用户名 → 姓名/用户名包含。匹配多人即歧义，由调用方返回候选。
     * 注意：用户名只用于匹配，展示一律用 {@link #displayName}（脱敏）。
     */
    static Resolution<RosterRow> resolveStudent(List<RosterRow> roster, String ref) {
        if (!StringUtils.hasText(ref)) {
            return new Resolution<>(List.of());
        }
        String q = ref.trim();
        if (q.matches("\\d{1,18}")) {
            long id = Long.parseLong(q);
            List<RosterRow> byId = roster.stream().filter(r -> Objects.equals(r.getStudentId(), id)).toList();
            if (!byId.isEmpty()) {
                return new Resolution<>(byId);
            }
        }
        String lq = q.toLowerCase(Locale.ROOT);
        List<RosterRow> exact = roster.stream()
                .filter(r -> lq.equals(lower(r.getRealName())) || lq.equals(lower(r.getUsername())))
                .toList();
        if (!exact.isEmpty()) {
            return new Resolution<>(exact);
        }
        return new Resolution<>(roster.stream()
                .filter(r -> lower(r.getRealName()).contains(lq) || lower(r.getUsername()).contains(lq))
                .toList());
    }

    /** 精确 nodeCode → 精确名称 → 名称包含。 */
    static Resolution<KgNode> resolveNode(java.util.Collection<KgNode> nodes, String ref) {
        if (!StringUtils.hasText(ref)) {
            return new Resolution<>(List.of());
        }
        String lq = ref.trim().toLowerCase(Locale.ROOT);
        List<KgNode> byCode = nodes.stream().filter(n -> lq.equals(lower(n.getNodeCode()))).toList();
        if (!byCode.isEmpty()) {
            return new Resolution<>(byCode);
        }
        List<KgNode> exact = nodes.stream().filter(n -> lq.equals(lower(n.getName()))).toList();
        if (!exact.isEmpty()) {
            return new Resolution<>(exact);
        }
        return new Resolution<>(nodes.stream()
                .filter(n -> lower(n.getName()).contains(lq))
                .sorted(Comparator.comparing(KgNode::getNodeCode))
                .toList());
    }

    // ───────────────────────── helpers: 数据装载 ─────────────────────────

    private record Cell(long studentId, KgNode node, double effective, double raw) {
    }

    private record DropInfo(double drop, String nodeName) {
    }

    private Map<Long, KgNode> activeNodes(long courseId) {
        Map<Long, KgNode> map = new LinkedHashMap<>();
        for (KgNode n : nullSafe(nodeMapper.selectList(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId)
                .isNull(KgNode::getArchivedAt)
                .orderByAsc(KgNode::getId)))) {
            map.put(n.getId(), n);
        }
        return map;
    }

    private static Set<Long> rosterIds(List<RosterRow> roster) {
        Set<Long> ids = new LinkedHashSet<>();
        roster.forEach(r -> ids.add(r.getStudentId()));
        return ids;
    }

    private List<Cell> cells(List<StudentMastery> rows, Map<Long, KgNode> nodes, Set<Long> studentIds) {
        List<Cell> out = new ArrayList<>();
        for (StudentMastery m : nullSafe(rows)) {
            KgNode node = nodes.get(m.getNodeId());
            if (node == null || m.getMasteryScore() == null || !studentIds.contains(m.getStudentId())) {
                continue;
            }
            out.add(new Cell(m.getStudentId(), node, decay.effectiveScoreOf(m), m.getMasteryScore().doubleValue()));
        }
        return out;
    }

    private static Map<Long, List<Cell>> groupByStudent(List<Cell> cells) {
        Map<Long, List<Cell>> map = new HashMap<>();
        cells.forEach(c -> map.computeIfAbsent(c.studentId(), k -> new ArrayList<>()).add(c));
        return map;
    }

    private Map<Long, StudentActivityRow> activityByStudent(long courseId, LocalDateTime now) {
        Map<Long, StudentActivityRow> map = new HashMap<>();
        for (StudentActivityRow a : nullSafe(analyticsMapper.selectStudentActivity(
                courseId, now.minusDays(7), now.minusDays(14)))) {
            map.put(a.getStudentId(), a);
        }
        return map;
    }

    /**
     * 近 7 天个体下降：7 天前最近快照按其时点衰减到 7 天前的有效分，对比当前有效分（0–1 口径），
     * 阈值判定交给 {@link ReportAggregations#anomalies}；每名学生取下降最大的知识点。
     */
    private Map<Long, DropInfo> sevenDayDrops(long courseId, LocalDateTime now, List<Cell> current,
                                              Map<Long, KgNode> nodes) {
        Map<String, String> nameByCode = new HashMap<>();
        List<ReportAggregations.EffectiveDelta> deltas = sevenDayDeltas(courseId, now, current, nameByCode);
        Map<Long, DropInfo> out = new HashMap<>();
        for (ReportAggregations.Anomaly a : ReportAggregations.anomalies(deltas, List.of(), now.toLocalDate())) {
            if (a.type() != ReportAggregations.AnomalyType.EFFECTIVE_DROP) {
                continue;
            }
            DropInfo prev = out.get(a.studentId());
            if (prev == null || a.detail() > prev.drop()) {
                out.put(a.studentId(), new DropInfo(a.detail(), nameByCode.get(a.nodeCode())));
            }
        }
        return out;
    }

    /** 每个 (学生, 节点) 的 7 天前→当前有效分（0–1 口径）；{@code nameByCode} 收集出现过的节点名。 */
    private List<ReportAggregations.EffectiveDelta> sevenDayDeltas(long courseId, LocalDateTime now,
                                                                   List<Cell> current,
                                                                   Map<String, String> nameByCode) {
        LocalDateTime cutoff = now.minusDays(7);
        Map<String, Cell> currentByKey = new HashMap<>();
        current.forEach(c -> currentByKey.put(c.studentId() + ":" + c.node().getId(), c));
        List<ReportAggregations.EffectiveDelta> deltas = new ArrayList<>();
        for (StudentMastery before : nullSafe(analyticsMapper.selectSnapshotsAtOrBefore(courseId, cutoff))) {
            Cell now0 = currentByKey.get(before.getStudentId() + ":" + before.getNodeId());
            if (now0 == null || before.getMasteryScore() == null) {
                continue;
            }
            double days = before.getUpdatedAt() == null ? 0
                    : Duration.between(before.getUpdatedAt(), cutoff).toMillis() / (24.0 * 3600 * 1000);
            double effBefore = decay.effectiveScoreOf(before, Math.max(0, days));
            deltas.add(new ReportAggregations.EffectiveDelta(before.getStudentId(), now0.node().getNodeCode(),
                    effBefore / 100.0, now0.effective() / 100.0));
            nameByCode.put(now0.node().getNodeCode(), now0.node().getName());
        }
        return deltas;
    }

    private List<ChapterView> chapterViews(java.util.Collection<KgNode> nodes, List<Cell> cells) {
        Map<String, int[]> nodeCount = new LinkedHashMap<>();
        for (KgNode n : nodes) {
            nodeCount.computeIfAbsent(chapterOf(n), k -> new int[1])[0]++;
        }
        Map<String, double[]> sums = new HashMap<>();
        for (Cell c : cells) {
            double[] s = sums.computeIfAbsent(chapterOf(c.node()), k -> new double[2]);
            s[0] += c.effective();
            s[1]++;
        }
        List<ChapterView> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : nodeCount.entrySet()) {
            double[] s = sums.get(e.getKey());
            if (s == null || s[1] == 0) {
                continue;
            }
            out.add(new ChapterView(e.getKey(), e.getValue()[0], round1(s[0] / s[1]), (int) s[1]));
            if (out.size() >= MAX_CHAPTERS) {
                break;
            }
        }
        return out;
    }

    private static String chapterOf(KgNode n) {
        return StringUtils.hasText(n.getChapter()) ? n.getChapter().trim() : NO_CHAPTER;
    }

    private static double avgOf(List<Cell> cells) {
        double s = 0;
        for (Cell c : cells) {
            s += c.effective();
        }
        return s / cells.size();
    }

    /** 复习积压：曾达掌握线（raw ≥ 掌握线）、读时衰减后有效分已跌破掌握线（口径同复习队列）。 */
    private static int backlogOf(List<Cell> cells, double masteredThreshold) {
        int n = 0;
        for (Cell c : cells) {
            if (c.raw() >= masteredThreshold && c.effective() < masteredThreshold) {
                n++;
            }
        }
        return n;
    }

    private static Integer daysSince(LocalDateTime last, LocalDateTime now) {
        if (last == null) {
            return null;
        }
        return (int) Math.max(0, ChronoUnit.DAYS.between(last.toLocalDate(), now.toLocalDate()));
    }

    static List<String> parseKeys(String answer) {
        if (!StringUtils.hasText(answer)) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String part : answer.split("[,，、\\s]+")) {
            if (StringUtils.hasText(part)) {
                keys.add(normalizeKey(part));
            }
        }
        return List.copyOf(keys);
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
    }

    private static NodeRef nodeRef(MistakeVO.NodeRef n) {
        return n == null ? null : new NodeRef(n.nodeCode(), n.name());
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
