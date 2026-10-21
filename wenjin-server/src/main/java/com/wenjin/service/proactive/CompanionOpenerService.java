package com.wenjin.service.proactive;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.dto.CompanionOpenerVO;
import com.wenjin.dto.ReviewTodayVO;
import com.wenjin.entity.AgentPlan;
import com.wenjin.entity.AgentPlanTask;
import com.wenjin.entity.AnswerRecord;
import com.wenjin.entity.CompanionHintLog;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.PracticeSession;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.AgentPlanMapper;
import com.wenjin.mapper.AgentPlanTaskMapper;
import com.wenjin.mapper.AnswerRecordMapper;
import com.wenjin.mapper.CompanionHintLogMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.PracticeSessionMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.service.ReviewService;
import com.wenjin.service.companion.GetLearningFrontierTool;
import com.wenjin.service.companion.ToolContext;
import com.wenjin.support.plan.PlanStatus;
import com.wenjin.support.plan.PlanTaskKind;
import com.wenjin.support.plan.PlanTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 伴侣主动陪伴（方向 4）：空会话 opener（问候 + 动作卡）与事件型提示条。
 * <p><b>零 LLM、确定性</b>：全部来自读时聚合的既有数据（复习队列、计划、练习错题、掌握度、前沿）；
 * 构造器不注入任何 AI 客户端。各信号来源独立 try/catch，单个来源失败只缺该项，不拖垮整个 opener。</p>
 * <p>提示条频控：已 dismiss 不再出；已展示未关闭的同键提示保持展示（粘性）；
 * 新实例须同时满足「同类型冷却期内未展示过」与「今日展示数未达上限」。</p>
 */
@Service
public class CompanionOpenerService {

    private static final Logger log = LoggerFactory.getLogger(CompanionOpenerService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_HINT_KEY = 96;
    private static final int MAX_HINT_TYPE = 32;

    static final String PROMPT_REVIEW_MISTAKES = "帮我复盘一下刚才那次练习的错题";

    private final FeatureFlagsProperties flags;
    private final LimitsProperties limits;
    private final ReviewService reviewService;
    private final AgentPlanMapper planMapper;
    private final AgentPlanTaskMapper taskMapper;
    private final PracticeSessionMapper sessionMapper;
    private final AnswerRecordMapper answerMapper;
    private final KgNodeMapper nodeMapper;
    private final StudentMasteryMapper masteryMapper;
    private final MasteryDecayCalculator decay;
    private final GetLearningFrontierTool frontierTool;
    private final CompanionHintLogMapper hintMapper;
    private final Clock clock;

    public CompanionOpenerService(FeatureFlagsProperties flags,
                                  LimitsProperties limits,
                                  ReviewService reviewService,
                                  AgentPlanMapper planMapper,
                                  AgentPlanTaskMapper taskMapper,
                                  PracticeSessionMapper sessionMapper,
                                  AnswerRecordMapper answerMapper,
                                  KgNodeMapper nodeMapper,
                                  StudentMasteryMapper masteryMapper,
                                  MasteryDecayCalculator decay,
                                  GetLearningFrontierTool frontierTool,
                                  CompanionHintLogMapper hintMapper,
                                  Clock clock) {
        this.flags = Objects.requireNonNull(flags, "flags");
        this.limits = limits == null ? new LimitsProperties() : limits;
        this.reviewService = reviewService;
        this.planMapper = planMapper;
        this.taskMapper = taskMapper;
        this.sessionMapper = sessionMapper;
        this.answerMapper = answerMapper;
        this.nodeMapper = nodeMapper;
        this.masteryMapper = masteryMapper;
        this.decay = decay;
        this.frontierTool = frontierTool;
        this.hintMapper = hintMapper;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    // ───────────────────────── 对外 ─────────────────────────

    /** 构造 opener，并把本次返回的提示记为已展示。 */
    public CompanionOpenerVO build(long studentId, long courseId) {
        requireEnabled();
        LocalDateTime now = LocalDateTime.now(clock);
        Signals sig = new Signals(studentId, courseId, now);

        CompanionOpenerVO vo = new CompanionOpenerVO();
        vo.setActions(buildActions(sig));
        List<CompanionOpenerVO.Hint> hints = selectVisibleHints(sig, buildHintCandidates(sig));
        for (CompanionOpenerVO.Hint h : hints) {
            try {
                hintMapper.upsertShown(studentId, courseId, h.getHintKey(), h.getHintType(), now);
            } catch (Exception e) {
                log.warn("record hint shown failed key={}: {}", h.getHintKey(), e.getMessage());
            }
        }
        vo.setHints(hints);
        vo.setGreeting(greeting(now, !vo.getActions().isEmpty()));
        return vo;
    }

    /** 当前可见提示数（只读，不记 shown）；供导航小红点。 */
    public int hintCount(long studentId, long courseId) {
        requireEnabled();
        Signals sig = new Signals(studentId, courseId, LocalDateTime.now(clock));
        return selectVisibleHints(sig, buildHintCandidates(sig)).size();
    }

    /** 关闭提示。studentId 必须来自登录主体（控制器保证）。 */
    public void dismiss(long studentId, long courseId, String hintKey) {
        requireEnabled();
        if (!StringUtils.hasText(hintKey) || hintKey.length() > MAX_HINT_KEY) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "hintKey 不合法");
        }
        String key = hintKey.trim();
        hintMapper.upsertDismissed(studentId, courseId, key, typeOfKey(key), LocalDateTime.now(clock));
    }

    private void requireEnabled() {
        if (!flags.isProactiveEnabled()) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
    }

    // ───────────────────────── 信号（懒加载 + 缓存） ─────────────────────────

    /** 一次请求内各信号来源的惰性缓存；来源失败返回空。 */
    private final class Signals {
        final long studentId;
        final long courseId;
        final LocalDateTime now;
        final LocalDate today;
        private Burst burst;
        private boolean burstLoaded;
        private ReviewTodayVO review;
        private boolean reviewLoaded;
        private PlanSignal plan;
        private boolean planLoaded;
        private Map<String, KgNode> nodesByCode;
        private Map<Long, KgNode> nodesById;
        private List<StudentMastery> masteries;

        Signals(long studentId, long courseId, LocalDateTime now) {
            this.studentId = studentId;
            this.courseId = courseId;
            this.now = now;
            this.today = now.toLocalDate();
        }

        Burst burst() {
            if (!burstLoaded) {
                burstLoaded = true;
                burst = safe("burst", () -> loadBurst(this), null);
            }
            return burst;
        }

        ReviewTodayVO review() {
            if (!reviewLoaded) {
                reviewLoaded = true;
                review = safe("review", () -> reviewService.getToday(studentId, courseId), null);
            }
            return review;
        }

        PlanSignal plan() {
            if (!planLoaded) {
                planLoaded = true;
                plan = safe("plan", () -> loadPlan(this), null);
            }
            return plan;
        }

        void loadNodes() {
            if (nodesByCode != null) {
                return;
            }
            nodesByCode = new LinkedHashMap<>();
            nodesById = new LinkedHashMap<>();
            List<KgNode> nodes = safe("nodes", () -> nodeMapper.selectList(new LambdaQueryWrapper<KgNode>()
                    .eq(KgNode::getCourseId, courseId)
                    .isNull(KgNode::getArchivedAt)), List.of());
            for (KgNode n : nodes == null ? List.<KgNode>of() : nodes) {
                nodesByCode.putIfAbsent(n.getNodeCode(), n);
                nodesById.putIfAbsent(n.getId(), n);
            }
        }

        KgNode nodeByCode(String code) {
            loadNodes();
            return code == null ? null : nodesByCode.get(code);
        }

        KgNode nodeById(Long id) {
            loadNodes();
            return id == null ? null : nodesById.get(id);
        }

        List<StudentMastery> masteries() {
            if (masteries == null) {
                List<StudentMastery> rows = safe("mastery", () -> masteryMapper.selectList(
                        new LambdaQueryWrapper<StudentMastery>()
                                .eq(StudentMastery::getStudentId, studentId)
                                .eq(StudentMastery::getCourseId, courseId)), List.of());
                masteries = rows == null ? List.of() : rows;
            }
            return masteries;
        }
    }

    private record Burst(long sessionId, Long nodeId, int total, int wrong) {}

    private record PlanSignal(long planId, List<AgentPlanTask> overdue, List<AgentPlanTask> todays) {}

    private <T> T safe(String what, Supplier<T> s, T fallback) {
        try {
            return s.get();
        } catch (Exception e) {
            log.warn("proactive signal '{}' unavailable: {}", what, e.getMessage());
            return fallback;
        }
    }

    /** 窗口内最近一次已提交练习；错题数 ≥ 阈值才算 burst。 */
    private Burst loadBurst(Signals sig) {
        LimitsProperties.Proactive cfg = limits.getProactive();
        LocalDateTime since = sig.now.minusHours(cfg.getErrorBurstWindowHours());
        List<PracticeSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<PracticeSession>()
                .eq(PracticeSession::getStudentId, sig.studentId)
                .eq(PracticeSession::getCourseId, sig.courseId)
                .eq(PracticeSession::getStatus, 1)
                .ge(PracticeSession::getSubmittedAt, since)
                .orderByDesc(PracticeSession::getSubmittedAt)
                .last("LIMIT 1"));
        if (sessions == null || sessions.isEmpty()) {
            return null;
        }
        PracticeSession ps = sessions.get(0);
        List<AnswerRecord> answers = answerMapper.selectList(new LambdaQueryWrapper<AnswerRecord>()
                .eq(AnswerRecord::getStudentId, sig.studentId)
                .eq(AnswerRecord::getSessionId, ps.getId())
                .eq(AnswerRecord::getScene, 2));
        if (answers == null || answers.isEmpty()) {
            return null;
        }
        int wrong = (int) answers.stream().filter(a -> a.getIsCorrect() != null && a.getIsCorrect() == 0).count();
        if (wrong < cfg.getErrorBurstThreshold() || wrong == 0) {
            return null;
        }
        return new Burst(ps.getId(), ps.getNodeId(), answers.size(), wrong);
    }

    /** 仅 ACTIVE 计划（DRAFT 永不进入）+ plan-enabled；任务按日期、id 升序。 */
    private PlanSignal loadPlan(Signals sig) {
        if (!flags.isPlanEnabled()) {
            return null;
        }
        List<AgentPlan> plans = planMapper.selectList(new LambdaQueryWrapper<AgentPlan>()
                .eq(AgentPlan::getStudentId, sig.studentId)
                .eq(AgentPlan::getCourseId, sig.courseId)
                .eq(AgentPlan::getStatus, PlanStatus.ACTIVE));
        AgentPlan active = plans == null ? null : plans.stream()
                .filter(p -> p.getStatus() != null && p.getStatus() == PlanStatus.ACTIVE)
                .findFirst().orElse(null);
        if (active == null) {
            return null;
        }
        List<AgentPlanTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<AgentPlanTask>()
                .eq(AgentPlanTask::getPlanId, active.getId())
                .eq(AgentPlanTask::getStatus, PlanTaskStatus.PENDING)
                .le(AgentPlanTask::getTaskDate, sig.today));
        List<AgentPlanTask> overdue = new ArrayList<>();
        List<AgentPlanTask> todays = new ArrayList<>();
        Comparator<AgentPlanTask> order = Comparator.comparing(AgentPlanTask::getTaskDate)
                .thenComparing(AgentPlanTask::getId, Comparator.nullsLast(Comparator.naturalOrder()));
        for (AgentPlanTask t : tasks == null ? List.<AgentPlanTask>of() : tasks) {
            if (t.getTaskDate() == null || t.getStatus() == null || t.getStatus() != PlanTaskStatus.PENDING) {
                continue;
            }
            if (t.getTaskDate().isBefore(sig.today)) {
                overdue.add(t);
            } else if (t.getTaskDate().isEqual(sig.today)) {
                todays.add(t);
            }
        }
        overdue.sort(order);
        todays.sort(order);
        return new PlanSignal(active.getId(), overdue, todays);
    }

    // ───────────────────────── 动作 ─────────────────────────

    private List<CompanionOpenerVO.Action> buildActions(Signals sig) {
        int max = Math.max(0, limits.getProactive().getMaxOpenerActions());
        List<CompanionOpenerVO.Action> out = new ArrayList<>();
        Set<String> usedNodes = new HashSet<>();
        // 来源按优先级惰性展开：够数即停，后续来源不再查询。
        List<Supplier<CompanionOpenerVO.Action>> sources = List.of(
                () -> mistakesAction(sig),
                () -> dueAction(sig),
                () -> planTaskAction(sig),
                () -> weakAction(sig),
                () -> frontierAction(sig));
        for (Supplier<CompanionOpenerVO.Action> src : sources) {
            if (out.size() >= max) {
                break;
            }
            CompanionOpenerVO.Action a = safe("action", src, null);
            if (a == null) {
                continue;
            }
            if (a.getNodeCode() != null && !usedNodes.add(a.getNodeCode())) {
                continue;
            }
            out.add(a);
        }
        return out;
    }

    private CompanionOpenerVO.Action mistakesAction(Signals sig) {
        Burst b = sig.burst();
        if (b == null) {
            return null;
        }
        KgNode node = sig.nodeById(b.nodeId());
        CompanionOpenerVO.Action a = new CompanionOpenerVO.Action();
        a.setKind("REVIEW_MISTAKES");
        a.setTitle("复盘刚才的 " + b.wrong() + " 道错题");
        a.setReason("上次练习 " + b.total() + " 题错 " + b.wrong() + " 题");
        a.setNodeCode(node == null ? null : node.getNodeCode());
        a.setNodeName(node == null ? null : node.getName());
        a.setCta(cta(PROMPT_REVIEW_MISTAKES, b.sessionId(), null));
        return a;
    }

    private CompanionOpenerVO.Action dueAction(Signals sig) {
        ReviewTodayVO.ReviewItemVO it = firstDue(sig.review());
        if (it == null) {
            return null;
        }
        String name = displayName(it.getName(), it.getNodeCode());
        long days = Math.max(1L, (long) Math.floor(it.getDaysSinceEvidence() == null ? 1.0 : it.getDaysSinceEvidence()));
        CompanionOpenerVO.Action a = new CompanionOpenerVO.Action();
        a.setKind("REVIEW_DUE");
        a.setTitle("「" + name + "」正在遗忘，花 5 分钟复习");
        a.setReason("已 " + days + " 天未复习，掌握度 " + fmt(it.getRawScore()) + "→" + fmt(it.getEffectiveScore()));
        a.setNodeCode(it.getNodeCode());
        a.setNodeName(name);
        a.setCta(cta("带我复习一下「" + name + "」，出两道题", null, null));
        return a;
    }

    private CompanionOpenerVO.Action planTaskAction(Signals sig) {
        PlanSignal p = sig.plan();
        if (p == null) {
            return null;
        }
        AgentPlanTask t = !p.overdue().isEmpty() ? p.overdue().get(0)
                : !p.todays().isEmpty() ? p.todays().get(0) : null;
        if (t == null) {
            return null;
        }
        KgNode node = sig.nodeByCode(t.getNodeCode());
        String name = displayName(node == null ? null : node.getName(), t.getNodeCode());
        String kind = kindLabel(t.getTaskKind());
        long late = ChronoUnit.DAYS.between(t.getTaskDate(), sig.today);
        CompanionOpenerVO.Action a = new CompanionOpenerVO.Action();
        a.setKind("PLAN_TASK");
        a.setTitle("计划任务：" + kind + "「" + name + "」");
        a.setReason(late > 0 ? "计划任务已逾期 " + late + " 天，先补上它" : "今日计划任务，完成它就保持进度");
        a.setNodeCode(t.getNodeCode());
        a.setNodeName(name);
        a.setCta(cta("带我完成计划里的任务：" + kind + "「" + name + "」", null, null));
        return a;
    }

    /** 参考 CompanionDegradeService.pickPracticeTarget：effective 最低且未达 mastered。 */
    private CompanionOpenerVO.Action weakAction(Signals sig) {
        double mastered = decay.getMasteredThreshold();
        StudentMastery weakest = null;
        double weakestEff = Double.MAX_VALUE;
        for (StudentMastery m : sig.masteries()) {
            if (m == null || m.getMasteryScore() == null || sig.nodeById(m.getNodeId()) == null) {
                continue;
            }
            double eff = decay.effectiveScoreOf(m);
            if (eff < mastered && eff < weakestEff) {
                weakest = m;
                weakestEff = eff;
            }
        }
        if (weakest == null) {
            return null;
        }
        KgNode node = sig.nodeById(weakest.getNodeId());
        CompanionOpenerVO.Action a = new CompanionOpenerVO.Action();
        a.setKind("WEAK_NODE");
        a.setTitle("巩固「" + node.getName() + "」");
        a.setReason("当前有效掌握度 " + fmt(weakestEff) + "，是你目前最薄弱的一环");
        a.setNodeCode(node.getNodeCode());
        a.setNodeName(node.getName());
        a.setCta(cta("陪我巩固一下「" + node.getName() + "」，出两道题", null, null));
        return a;
    }

    private CompanionOpenerVO.Action frontierAction(Signals sig) {
        try {
            String json = frontierTool.execute(new ToolContext(sig.studentId, sig.courseId), JSON.createObjectNode());
            JsonNode items = JSON.readTree(json).path("items");
            if (!items.isArray() || items.isEmpty()) {
                return null;
            }
            JsonNode first = items.get(0);
            String code = first.path("code").asText(null);
            String name = displayName(first.path("name").asText(null), code);
            if (!StringUtils.hasText(code)) {
                return null;
            }
            CompanionOpenerVO.Action a = new CompanionOpenerVO.Action();
            a.setKind("FRONTIER");
            a.setTitle("开始学习「" + name + "」");
            a.setReason("它的前置知识已掌握，现在最适合往下学");
            a.setNodeCode(code);
            a.setNodeName(name);
            a.setCta(cta("带我学一下「" + name + "」", null, null));
            return a;
        } catch (Exception e) {
            log.warn("proactive frontier unavailable: {}", e.getMessage());
            return null;
        }
    }

    // ───────────────────────── 提示条 ─────────────────────────

    private List<CompanionOpenerVO.Hint> buildHintCandidates(Signals sig) {
        List<CompanionOpenerVO.Hint> out = new ArrayList<>();

        Burst b = sig.burst();
        if (b != null) {
            KgNode node = sig.nodeById(b.nodeId());
            CompanionOpenerVO.Hint h = hint("ERROR_BURST:session:" + b.sessionId(), "ERROR_BURST",
                    "刚才那次练习错了 " + b.wrong() + " 题",
                    "共 " + b.total() + " 题错 " + b.wrong() + " 题，趁热复盘效果最好",
                    cta(PROMPT_REVIEW_MISTAKES, b.sessionId(), node == null ? null : node.getNodeCode()));
            out.add(h);
        }

        ReviewTodayVO review = sig.review();
        if (review != null && review.getTotalDecayingCount() > 0) {
            ReviewTodayVO.ReviewItemVO it = firstDue(review);
            String name = it == null ? null : displayName(it.getName(), it.getNodeCode());
            CompanionOpenerVO.Cta c = name == null ? null
                    : cta("带我复习一下「" + name + "」，出两道题", null, it.getNodeCode());
            out.add(hint("DECAY:" + sig.today, "DECAY",
                    review.getTotalDecayingCount() + " 个知识点正在遗忘",
                    name == null ? "有掌握过的知识点很久没复习，掌握度在下降"
                            : "「" + name + "」等很久没复习，掌握度在下降",
                    c));
        }

        PlanSignal p = sig.plan();
        if (p != null && !p.overdue().isEmpty()) {
            AgentPlanTask earliest = p.overdue().get(0);
            long late = ChronoUnit.DAYS.between(earliest.getTaskDate(), sig.today);
            out.add(hint("PLAN_BEHIND:plan:" + p.planId() + ":" + sig.today, "PLAN_BEHIND",
                    "学习计划落后了 " + p.overdue().size() + " 个任务",
                    "最早的一个已逾期 " + late + " 天",
                    cta("帮我看看学习计划进度，要不要调整一下", null, null)));
        }
        return out;
    }

    private List<CompanionOpenerVO.Hint> selectVisibleHints(Signals sig, List<CompanionOpenerVO.Hint> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        LimitsProperties.Proactive cfg = limits.getProactive();
        List<CompanionHintLog> logs = safe("hintLog", () -> hintMapper.selectList(
                new LambdaQueryWrapper<CompanionHintLog>()
                        .eq(CompanionHintLog::getStudentId, sig.studentId)
                        .eq(CompanionHintLog::getCourseId, sig.courseId)), List.of());
        if (logs == null) {
            logs = List.of();
        }
        Map<String, CompanionHintLog> byKey = new LinkedHashMap<>();
        for (CompanionHintLog l : logs) {
            byKey.put(l.getHintKey(), l);
        }
        LocalDateTime cooldownStart = sig.now.minusHours(cfg.getHintCooldownHours());
        int shownToday = (int) logs.stream()
                .filter(l -> l.getShownAt() != null && l.getShownAt().toLocalDate().equals(sig.today))
                .count();

        List<CompanionOpenerVO.Hint> out = new ArrayList<>();
        for (CompanionOpenerVO.Hint cand : candidates) {
            CompanionHintLog existing = byKey.get(cand.getHintKey());
            if (existing != null) {
                if (existing.getDismissedAt() == null) {
                    out.add(cand); // 粘性：已展示未关闭，继续展示
                }
                continue;
            }
            boolean cooling = logs.stream().anyMatch(l -> cand.getHintType().equals(l.getHintType())
                    && l.getShownAt() != null && l.getShownAt().isAfter(cooldownStart));
            if (cooling || shownToday >= cfg.getMaxHintsPerDay()) {
                continue;
            }
            shownToday++;
            out.add(cand);
        }
        return out;
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private static ReviewTodayVO.ReviewItemVO firstDue(ReviewTodayVO review) {
        if (review == null || review.getItems() == null) {
            return null;
        }
        return review.getItems().stream()
                .filter(i -> i != null && StringUtils.hasText(i.getNodeCode()))
                .findFirst().orElse(null);
    }

    private static CompanionOpenerVO.Cta cta(String prompt, Long reviewSessionId, String nodeCode) {
        CompanionOpenerVO.Cta c = new CompanionOpenerVO.Cta();
        c.setPrompt(prompt);
        c.setReviewPracticeSessionId(reviewSessionId);
        c.setNodeCode(nodeCode);
        return c;
    }

    private static CompanionOpenerVO.Hint hint(String key, String type, String title, String reason,
                                               CompanionOpenerVO.Cta cta) {
        CompanionOpenerVO.Hint h = new CompanionOpenerVO.Hint();
        h.setHintKey(key);
        h.setHintType(type);
        h.setTitle(title);
        h.setReason(reason);
        h.setCta(cta);
        return h;
    }

    private static String typeOfKey(String key) {
        int i = key.indexOf(':');
        String t = i > 0 ? key.substring(0, i) : key;
        return t.length() > MAX_HINT_TYPE ? t.substring(0, MAX_HINT_TYPE) : t;
    }

    private static String displayName(String name, String code) {
        return StringUtils.hasText(name) ? name : code;
    }

    private static String fmt(Double v) {
        return v == null ? "?" : String.valueOf(Math.round(v));
    }

    private static String kindLabel(Integer kind) {
        if (kind == null) {
            return "学习";
        }
        return switch (kind) {
            case PlanTaskKind.REVIEW -> "复习";
            case PlanTaskKind.PRACTICE -> "练习";
            default -> "学习";
        };
    }

    static String greeting(LocalDateTime now, boolean hasActions) {
        int h = now.getHour();
        String hello = h < 5 ? "夜深了" : h < 11 ? "上午好" : h < 14 ? "中午好" : h < 18 ? "下午好" : "晚上好";
        return hasActions
                ? hello + "，我帮你看了下学习情况，可以从下面这几件事开始："
                : hello + "，今天想学点什么？有问题随时问我。";
    }
}
