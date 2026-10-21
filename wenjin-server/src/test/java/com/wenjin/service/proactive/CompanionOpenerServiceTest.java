package com.wenjin.service.proactive;

import com.wenjin.common.BusinessException;
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
import com.wenjin.service.LearningFrontierService;
import com.wenjin.support.plan.PlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanionOpenerServiceTest {

    static final long SID = 2L;
    static final long CID = 5L;
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2026-10-21 10:00 上海 */
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 10, 0);
    static final LocalDate TODAY = NOW.toLocalDate();

    @Mock ReviewService reviewService;
    @Mock AgentPlanMapper planMapper;
    @Mock AgentPlanTaskMapper taskMapper;
    @Mock PracticeSessionMapper sessionMapper;
    @Mock AnswerRecordMapper answerMapper;
    @Mock KgNodeMapper nodeMapper;
    @Mock StudentMasteryMapper masteryMapper;
    @Mock MasteryDecayCalculator decay;
    @Mock LearningFrontierService frontierService;
    @Mock CompanionHintLogMapper hintMapper;

    FeatureFlagsProperties flags;
    LimitsProperties limits;
    CompanionOpenerService service;

    @BeforeEach
    void setup() throws Exception {
        flags = new FeatureFlagsProperties();
        flags.setProactiveEnabled(true);
        flags.setPlanEnabled(true);
        limits = new LimitsProperties();
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        service = new CompanionOpenerService(flags, limits, reviewService, planMapper, taskMapper,
                sessionMapper, answerMapper, nodeMapper, masteryMapper, decay, frontierService, hintMapper, clock);

        when(nodeMapper.selectList(any())).thenReturn(List.of(
                node(1L, "N1", "变量"), node(2L, "N2", "循环"), node(3L, "N3", "数组"), node(4L, "N4", "继承")));
        when(masteryMapper.selectList(any())).thenReturn(List.of());
        when(decay.getMasteredThreshold()).thenReturn(75.0);
        when(decay.effectiveScoreOf(any(StudentMastery.class)))
                .thenAnswer(i -> ((StudentMastery) i.getArgument(0)).getMasteryScore().doubleValue());
        when(reviewService.getToday(SID, CID)).thenReturn(review(0));
        when(planMapper.selectList(any())).thenReturn(List.of());
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(sessionMapper.selectList(any())).thenReturn(List.of());
        when(answerMapper.selectList(any())).thenReturn(List.of());
        when(hintMapper.selectList(any())).thenReturn(List.of());
        when(frontierService.frontier(SID, CID, 1)).thenReturn(List.of());
    }

    // ───────── helpers ─────────

    static KgNode node(long id, String code, String name) {
        KgNode n = new KgNode();
        n.setId(id);
        n.setCourseId(CID);
        n.setNodeCode(code);
        n.setName(name);
        return n;
    }

    static ReviewTodayVO review(int totalDecaying, ReviewTodayVO.ReviewItemVO... items) {
        ReviewTodayVO vo = new ReviewTodayVO();
        vo.setItems(List.of(items));
        vo.setTotalDecayingCount(totalDecaying);
        return vo;
    }

    static ReviewTodayVO.ReviewItemVO dueItem(String code, String name, double raw, double eff, double days) {
        ReviewTodayVO.ReviewItemVO i = new ReviewTodayVO.ReviewItemVO();
        i.setNodeCode(code);
        i.setName(name);
        i.setRawScore(raw);
        i.setEffectiveScore(eff);
        i.setDaysSinceEvidence(days);
        return i;
    }

    void givenBurst(long sessionId, long nodeId, int total, int wrong) {
        PracticeSession ps = new PracticeSession();
        ps.setId(sessionId);
        ps.setNodeId(nodeId);
        ps.setStatus(1);
        ps.setSubmittedAt(NOW.minusHours(1));
        when(sessionMapper.selectList(any())).thenReturn(List.of(ps));
        List<AnswerRecord> rows = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            AnswerRecord r = new AnswerRecord();
            r.setSessionId(sessionId);
            r.setIsCorrect(i < wrong ? 0 : 1);
            rows.add(r);
        }
        when(answerMapper.selectList(any())).thenReturn(rows);
    }

    void givenActivePlanWithTasks(AgentPlanTask... tasks) {
        AgentPlan p = new AgentPlan();
        p.setId(7L);
        p.setStatus(PlanStatus.ACTIVE);
        when(planMapper.selectList(any())).thenReturn(List.of(p));
        when(taskMapper.selectList(any())).thenReturn(List.of(tasks));
    }

    static AgentPlanTask task(long id, LocalDate date, String code, int kind) {
        AgentPlanTask t = new AgentPlanTask();
        t.setId(id);
        t.setPlanId(7L);
        t.setTaskDate(date);
        t.setNodeCode(code);
        t.setTaskKind(kind);
        t.setStatus(0);
        return t;
    }

    void givenMastery(long nodeId, double score) {
        List<StudentMastery> cur = new ArrayList<>(masteryMapper.selectList(any()));
        StudentMastery m = new StudentMastery();
        m.setNodeId(nodeId);
        m.setMasteryScore(BigDecimal.valueOf(score));
        cur.add(m);
        when(masteryMapper.selectList(any())).thenReturn(cur);
    }

    static CompanionHintLog log(String key, String type, LocalDateTime shownAt, LocalDateTime dismissedAt) {
        CompanionHintLog l = new CompanionHintLog();
        l.setStudentId(SID);
        l.setCourseId(CID);
        l.setHintKey(key);
        l.setHintType(type);
        l.setShownAt(shownAt);
        l.setDismissedAt(dismissedAt);
        return l;
    }

    // ───────── 开关 / 零 LLM ─────────

    @Test
    @DisplayName("开关关闭：build / hintCount / dismiss 全部 404 且不碰数据")
    void disabled_all404() {
        flags.setProactiveEnabled(false);
        assertThatThrownBy(() -> service.build(SID, CID)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(404);
        assertThatThrownBy(() -> service.hintCount(SID, CID)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(404);
        assertThatThrownBy(() -> service.dismiss(SID, CID, "DECAY:x")).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(404);
        verifyNoInteractions(reviewService, planMapper, taskMapper, sessionMapper, answerMapper, nodeMapper,
                masteryMapper, hintMapper, frontierService);
    }

    @Test
    @DisplayName("零 LLM：构造器不依赖任何 com.wenjin.ai 类型")
    void zeroLlm_noAiDependency() {
        for (Constructor<?> c : CompanionOpenerService.class.getDeclaredConstructors()) {
            for (Class<?> p : c.getParameterTypes()) {
                assertThat(p.getName()).doesNotContain(".ai.").doesNotContain("CompanionAiClient");
            }
        }
    }

    // ───────── 动作 ─────────

    @Test
    @DisplayName("优先级：错题复盘 > 到期复习 > 计划任务 > 薄弱，取前 2")
    void actions_priorityAndCap() {
        givenBurst(123L, 2L, 3, 2);
        when(reviewService.getToday(SID, CID)).thenReturn(review(1, dueItem("N1", "变量", 82, 61, 9.4)));
        givenActivePlanWithTasks(task(1, TODAY, "N3", 1));
        givenMastery(4L, 30);

        CompanionOpenerVO vo = service.build(SID, CID);

        assertThat(vo.getActions()).hasSize(2);
        CompanionOpenerVO.Action a0 = vo.getActions().get(0);
        assertThat(a0.getKind()).isEqualTo("REVIEW_MISTAKES");
        assertThat(a0.getTitle()).isEqualTo("复盘刚才的 2 道错题");
        assertThat(a0.getReason()).isEqualTo("上次练习 3 题错 2 题");
        assertThat(a0.getNodeCode()).isEqualTo("N2");
        assertThat(a0.getNodeName()).isEqualTo("循环");
        assertThat(a0.getCta().getPrompt()).isEqualTo("帮我复盘一下刚才那次练习的错题");
        assertThat(a0.getCta().getReviewPracticeSessionId()).isEqualTo(123L);
        CompanionOpenerVO.Action a1 = vo.getActions().get(1);
        assertThat(a1.getKind()).isEqualTo("REVIEW_DUE");
        assertThat(a1.getTitle()).isEqualTo("「变量」正在遗忘，花 5 分钟复习");
        assertThat(a1.getReason()).isEqualTo("已 9 天未复习，掌握度 82→61");
        assertThat(a1.getCta().getPrompt()).isEqualTo("带我复习一下「变量」，出两道题");
        assertThat(a1.getCta().getReviewPracticeSessionId()).isNull();
    }

    @Test
    @DisplayName("错题数低于阈值 → 不出 REVIEW_MISTAKES，也不出 ERROR_BURST 提示")
    void burstBelowThreshold_noAction() {
        givenBurst(123L, 2L, 3, 1);
        CompanionOpenerVO vo = service.build(SID, CID);
        assertThat(vo.getActions()).isEmpty();
        assertThat(vo.getHints()).isEmpty();
    }

    @Test
    @DisplayName("同节点去重：错题复盘与到期复习同节点时，后者让位给下一优先级")
    void actions_dedupSameNode() {
        givenBurst(123L, 1L, 3, 2);
        when(reviewService.getToday(SID, CID)).thenReturn(review(1, dueItem("N1", "变量", 82, 61, 9.0)));
        givenActivePlanWithTasks(task(1, TODAY, "N3", 2));

        CompanionOpenerVO vo = service.build(SID, CID);

        assertThat(vo.getActions()).extracting(CompanionOpenerVO.Action::getKind)
                .containsExactly("REVIEW_MISTAKES", "PLAN_TASK");
        assertThat(vo.getActions().get(1).getNodeCode()).isEqualTo("N3");
    }

    @Test
    @DisplayName("maxOpenerActions 可配")
    void actions_capConfigurable() {
        limits.getProactive().setMaxOpenerActions(1);
        givenBurst(123L, 2L, 3, 2);
        when(reviewService.getToday(SID, CID)).thenReturn(review(1, dueItem("N1", "变量", 82, 61, 9.0)));
        assertThat(service.build(SID, CID).getActions()).hasSize(1);
    }

    @Test
    @DisplayName("计划任务：逾期优先于今日；文案区分")
    void planTask_overdueBeforeToday() {
        givenActivePlanWithTasks(task(2, TODAY, "N2", 0), task(1, TODAY.minusDays(2), "N3", 1));
        CompanionOpenerVO.Action a = service.build(SID, CID).getActions().get(0);
        assertThat(a.getKind()).isEqualTo("PLAN_TASK");
        assertThat(a.getNodeCode()).isEqualTo("N3");
        assertThat(a.getReason()).contains("逾期 2 天");
    }

    @Test
    @DisplayName("计划任务：今日任务文案")
    void planTask_today() {
        givenActivePlanWithTasks(task(2, TODAY, "N2", 0));
        CompanionOpenerVO.Action a = service.build(SID, CID).getActions().get(0);
        assertThat(a.getTitle()).contains("循环");
        assertThat(a.getReason()).contains("今日");
        assertThat(a.getCta().getPrompt()).contains("循环");
    }

    @Test
    @DisplayName("DRAFT 计划不产生 PLAN_TASK / PLAN_BEHIND")
    void draftPlan_ignored() {
        AgentPlan p = new AgentPlan();
        p.setId(7L);
        p.setStatus(PlanStatus.DRAFT);
        when(planMapper.selectList(any())).thenReturn(List.of(p));
        when(taskMapper.selectList(any())).thenReturn(List.of(task(1, TODAY.minusDays(1), "N2", 0)));
        CompanionOpenerVO vo = service.build(SID, CID);
        assertThat(vo.getActions()).isEmpty();
        assertThat(vo.getHints()).isEmpty();
    }

    @Test
    @DisplayName("plan-enabled 关闭 → 不出计划动作与 PLAN_BEHIND，且不查计划")
    void planDisabled_noPlanSignals() {
        flags.setPlanEnabled(false);
        givenActivePlanWithTasks(task(1, TODAY.minusDays(1), "N2", 0));
        CompanionOpenerVO vo = service.build(SID, CID);
        assertThat(vo.getActions()).isEmpty();
        assertThat(vo.getHints()).isEmpty();
        verifyNoInteractions(taskMapper);
    }

    @Test
    @DisplayName("WEAK_NODE：取 effective 最低且低于 mastered 阈值的节点")
    void weakNode_lowestEffective() {
        givenMastery(1L, 70);
        givenMastery(2L, 35);
        givenMastery(3L, 90);
        CompanionOpenerVO.Action a = service.build(SID, CID).getActions().get(0);
        assertThat(a.getKind()).isEqualTo("WEAK_NODE");
        assertThat(a.getNodeCode()).isEqualTo("N2");
        assertThat(a.getReason()).contains("35");
    }

    @Test
    @DisplayName("FRONTIER：新学生无掌握数据 → 取前沿第一个节点")
    void frontier_forNewStudent() throws Exception {
        when(frontierService.frontier(SID, CID, 1))
                .thenReturn(List.of(new LearningFrontierService.FrontierNode("N1", "变量", 0.0)));
        CompanionOpenerVO vo = service.build(SID, CID);
        assertThat(vo.getActions()).hasSize(1);
        assertThat(vo.getActions().get(0).getKind()).isEqualTo("FRONTIER");
        assertThat(vo.getActions().get(0).getNodeCode()).isEqualTo("N1");
    }

    @Test
    @DisplayName("某个来源抛异常不拖垮整个 opener")
    void sourceFailure_isolated() {
        when(reviewService.getToday(SID, CID)).thenThrow(new RuntimeException("boom"));
        givenMastery(2L, 35);
        CompanionOpenerVO vo = service.build(SID, CID);
        assertThat(vo.getActions()).extracting(CompanionOpenerVO.Action::getKind).containsExactly("WEAK_NODE");
    }

    @Test
    @DisplayName("greeting：按时段，有动作/无动作两种模板")
    void greeting_byTimeOfDay() {
        String none = service.build(SID, CID).getGreeting();
        assertThat(none).startsWith("上午好");
        givenMastery(2L, 35);
        String some = service.build(SID, CID).getGreeting();
        assertThat(some).startsWith("上午好").isNotEqualTo(none);

        Clock evening = Clock.fixed(NOW.withHour(20).atZone(ZONE).toInstant(), ZONE);
        CompanionOpenerService s2 = new CompanionOpenerService(flags, limits, reviewService, planMapper, taskMapper,
                sessionMapper, answerMapper, nodeMapper, masteryMapper, decay, frontierService, hintMapper, evening);
        assertThat(s2.build(SID, CID).getGreeting()).startsWith("晚上好");
    }

    // ───────── 提示条 ─────────

    @Test
    @DisplayName("三类事件提示：key 含事件实例，且本次返回的记为 shown")
    void hints_allThreeAndMarkedShown() {
        givenBurst(123L, 2L, 3, 2);
        when(reviewService.getToday(SID, CID)).thenReturn(review(3, dueItem("N1", "变量", 82, 61, 9.0)));
        givenActivePlanWithTasks(task(1, TODAY.minusDays(1), "N3", 1));

        List<CompanionOpenerVO.Hint> hints = service.build(SID, CID).getHints();

        assertThat(hints).extracting(CompanionOpenerVO.Hint::getHintKey)
                .containsExactly("ERROR_BURST:session:123", "DECAY:2026-10-21", "PLAN_BEHIND:plan:7:2026-10-21");
        assertThat(hints).extracting(CompanionOpenerVO.Hint::getHintType)
                .containsExactly("ERROR_BURST", "DECAY", "PLAN_BEHIND");
        assertThat(hints.get(0).getCta().getReviewPracticeSessionId()).isEqualTo(123L);
        assertThat(hints.get(1).getCta().getNodeCode()).isEqualTo("N1");
        hints.forEach(h -> assertThat(h.getTitle()).isNotBlank());
        verify(hintMapper).upsertShown(SID, CID, "ERROR_BURST:session:123", "ERROR_BURST", NOW);
        verify(hintMapper).upsertShown(SID, CID, "DECAY:2026-10-21", "DECAY", NOW);
        verify(hintMapper).upsertShown(SID, CID, "PLAN_BEHIND:plan:7:2026-10-21", "PLAN_BEHIND", NOW);
    }

    @Test
    @DisplayName("PLAN_BEHIND 只看 task_date<今天 的 PENDING；仅今日任务不触发")
    void planBehind_onlyOverdue() {
        givenActivePlanWithTasks(task(1, TODAY, "N3", 1));
        assertThat(service.build(SID, CID).getHints()).isEmpty();
    }

    @Test
    @DisplayName("已 dismiss 的提示不再出")
    void hints_dismissedNotShown() {
        when(reviewService.getToday(SID, CID)).thenReturn(review(3, dueItem("N1", "变量", 82, 61, 9.0)));
        when(hintMapper.selectList(any())).thenReturn(List.of(
                log("DECAY:2026-10-21", "DECAY", NOW.minusHours(2), NOW.minusHours(1))));
        assertThat(service.build(SID, CID).getHints()).isEmpty();
        verify(hintMapper, never()).upsertShown(anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("已展示未关闭的同键提示继续展示（粘性），但不刷新 shown_at（不把冷却往后推）")
    void hints_stickyUntilDismissed() {
        when(reviewService.getToday(SID, CID)).thenReturn(review(3, dueItem("N1", "变量", 82, 61, 9.0)));
        when(hintMapper.selectList(any())).thenReturn(List.of(
                log("DECAY:2026-10-21", "DECAY", NOW.minusHours(3), null)));
        assertThat(service.build(SID, CID).getHints()).hasSize(1);
        verify(hintMapper, never()).upsertShown(anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("冷却：同类型在冷却期内已展示过别的实例 → 新实例不出；超过冷却后可出")
    void hints_cooldownPerType() {
        when(reviewService.getToday(SID, CID)).thenReturn(review(3, dueItem("N1", "变量", 82, 61, 9.0)));
        when(hintMapper.selectList(any())).thenReturn(List.of(
                log("DECAY:2026-10-20", "DECAY", NOW.minusHours(19), NOW.minusHours(18))));
        assertThat(service.build(SID, CID).getHints()).isEmpty();

        when(hintMapper.selectList(any())).thenReturn(List.of(
                log("DECAY:2026-10-20", "DECAY", NOW.minusHours(21), null)));
        assertThat(service.build(SID, CID).getHints()).hasSize(1);
    }

    @Test
    @DisplayName("每日上限：今日已展示数达到上限后，新提示不再出")
    void hints_dailyCap() {
        limits.getProactive().setMaxHintsPerDay(1);
        when(reviewService.getToday(SID, CID)).thenReturn(review(3, dueItem("N1", "变量", 82, 61, 9.0)));
        givenActivePlanWithTasks(task(1, TODAY.minusDays(1), "N3", 1));
        when(hintMapper.selectList(any())).thenReturn(List.of(
                log("ERROR_BURST:session:9", "ERROR_BURST", NOW.minusHours(2), null)));
        assertThat(service.build(SID, CID).getHints()).isEmpty();

        // 上限=2：今日已 1 个，同次最多再放行 1 个（优先级靠前的 DECAY）
        limits.getProactive().setMaxHintsPerDay(2);
        List<CompanionOpenerVO.Hint> hints = service.build(SID, CID).getHints();
        assertThat(hints).hasSize(1);
        assertThat(hints.get(0).getHintType()).isEqualTo("DECAY");
    }

    @Test
    @DisplayName("hintCount：只读，不写 shown；与 build 口径一致")
    void hintCount_readOnly() {
        when(reviewService.getToday(SID, CID)).thenReturn(review(3, dueItem("N1", "变量", 82, 61, 9.0)));
        givenActivePlanWithTasks(task(1, TODAY.minusDays(1), "N3", 1));
        assertThat(service.hintCount(SID, CID)).isEqualTo(2);
        verify(hintMapper, never()).upsertShown(anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("hintCount：只数尚未展示过的新提示；看过 opener 后小红点清零（粘性提示不计）")
    void hintCount_onlyUnseen() {
        when(reviewService.getToday(SID, CID)).thenReturn(review(3, dueItem("N1", "变量", 82, 61, 9.0)));
        givenActivePlanWithTasks(task(1, TODAY.minusDays(1), "N3", 1));
        when(hintMapper.selectList(any())).thenReturn(List.of(
                log("DECAY:2026-10-21", "DECAY", NOW.minusHours(1), null)));
        // DECAY 已展示（粘性仍可见）不计；PLAN_BEHIND 是新的
        assertThat(service.hintCount(SID, CID)).isEqualTo(1);

        when(hintMapper.selectList(any())).thenReturn(List.of(
                log("DECAY:2026-10-21", "DECAY", NOW.minusHours(1), null),
                log("PLAN_BEHIND:plan:7:2026-10-21", "PLAN_BEHIND", NOW.minusMinutes(5), null)));
        assertThat(service.hintCount(SID, CID)).isZero();
    }

    // ───────── dismiss ─────────

    @Test
    @DisplayName("dismiss：以传入 studentId 记 dismissed_at，类型取 key 前缀")
    void dismiss_records() {
        service.dismiss(SID, CID, "PLAN_BEHIND:plan:7:2026-10-21");
        verify(hintMapper).upsertDismissed(eq(SID), eq(CID), eq("PLAN_BEHIND:plan:7:2026-10-21"),
                eq("PLAN_BEHIND"), eq(NOW));
    }

    @Test
    @DisplayName("dismiss：空 key / 超长 key → 400")
    void dismiss_invalidKey() {
        assertThatThrownBy(() -> service.dismiss(SID, CID, " "))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(400);
        assertThatThrownBy(() -> service.dismiss(SID, CID, "X".repeat(97)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(400);
    }
}
