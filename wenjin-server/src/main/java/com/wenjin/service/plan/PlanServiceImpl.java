package com.wenjin.service.plan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.dto.DraftPlanResultVO;
import com.wenjin.dto.PlanTaskVO;
import com.wenjin.dto.PlanVO;
import com.wenjin.entity.AgentPlan;
import com.wenjin.entity.AgentPlanTask;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.entity.StudentMastery;
import com.wenjin.mapper.AgentPlanMapper;
import com.wenjin.mapper.AgentPlanTaskMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.mapper.StudentMasteryMapper;
import com.wenjin.service.MasteryDecayCalculator;
import com.wenjin.support.PrereqDirection;
import com.wenjin.support.ReviewMarker;
import com.wenjin.support.plan.PlanAlgorithm;
import com.wenjin.support.plan.PlanErrorCodes;
import com.wenjin.support.plan.PlanGoalType;
import com.wenjin.support.plan.PlanStatus;
import com.wenjin.support.plan.PlanTaskKind;
import com.wenjin.support.plan.PlanTaskOrigin;
import com.wenjin.support.plan.PlanTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PlanServiceImpl implements PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanServiceImpl.class);

    private final AgentPlanMapper planMapper;
    private final AgentPlanTaskMapper taskMapper;
    private final KgNodeMapper nodeMapper;
    private final KgEdgeMapper edgeMapper;
    private final StudentMasteryMapper masteryMapper;
    private final MasteryDecayCalculator decayCalculator;
    private final FeatureFlagsProperties featureFlags;
    private final LimitsProperties limits;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public PlanServiceImpl(AgentPlanMapper planMapper,
                           AgentPlanTaskMapper taskMapper,
                           KgNodeMapper nodeMapper,
                           KgEdgeMapper edgeMapper,
                           StudentMasteryMapper masteryMapper,
                           MasteryDecayCalculator decayCalculator,
                           FeatureFlagsProperties featureFlags,
                           LimitsProperties limits,
                           Clock clock,
                           ObjectMapper objectMapper) {
        this.planMapper = planMapper;
        this.taskMapper = taskMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.masteryMapper = masteryMapper;
        this.decayCalculator = decayCalculator;
        this.featureFlags = featureFlags;
        this.limits = limits == null ? new LimitsProperties() : limits;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DraftPlanResultVO draft(long studentId, long courseId, LocalDate targetDate,
                                   List<String> scopeNodes, Long createdRunId) {
        requirePlanEnabled();
        LocalDate today = today();
        if (targetDate == null || !targetDate.isAfter(today.plusDays(1))) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "targetDate 须晚于今天+1天");
        }
        int maxScope = limits.getPlan().getMaxScopeNodes();
        List<String> scope = normalizeScope(scopeNodes, maxScope);

        // 旧 DRAFT 自动 CANCELLED
        cancelExistingDrafts(studentId, courseId);

        PlanAlgorithm.PlanResult algo = runAlgorithm(studentId, courseId, targetDate, scope);
        DraftPlanResultVO vo = new DraftPlanResultVO();
        if (!algo.ok()) {
            vo.setErrorCode(algo.errorCode());
            vo.setSuggestedScope(algo.suggestedScope());
            vo.setTaskCount(0);
            vo.setDays(0);
            vo.setExplain(null);
            return vo;
        }

        AgentPlan plan = new AgentPlan();
        plan.setStudentId(studentId);
        plan.setCourseId(courseId);
        plan.setGoalType(PlanGoalType.EXAM_PREP);
        plan.setTargetDate(targetDate);
        plan.setStatus(PlanStatus.DRAFT);
        plan.setScopeJson(scopeJson(scope, false));
        plan.setCreatedRunId(createdRunId);
        plan.setCreatedAt(now());
        planMapper.insert(plan);

        for (PlanAlgorithm.PlannedTask t : algo.tasks()) {
            AgentPlanTask row = new AgentPlanTask();
            row.setPlanId(plan.getId());
            row.setTaskDate(t.taskDate());
            row.setNodeCode(t.nodeCode());
            row.setTaskKind(t.taskKind());
            row.setStatus(PlanTaskStatus.PENDING);
            row.setOrigin(PlanTaskOrigin.INITIAL);
            taskMapper.insert(row);
        }

        vo.setPlanId(plan.getId());
        vo.setTaskCount(algo.tasks().size());
        vo.setDays((int) today.until(targetDate).getDays());
        // LLM 解释文案骨架：失败降级为空（计划照常返回）
        vo.setExplain(null);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanVO confirm(long studentId, long planId) {
        requirePlanEnabled();
        AgentPlan plan = requireOwnedPlan(studentId, planId);
        if (plan.getStatus() == null || plan.getStatus() != PlanStatus.DRAFT) {
            throw new BusinessException(ResultCode.CONFLICT, "仅 DRAFT 可确认",
                    PlanErrorCodes.PLAN_INVALID_TRANSITION);
        }
        // 同时至多 1 个 ACTIVE：取消既有 ACTIVE
        List<AgentPlan> actives = planMapper.selectList(new LambdaQueryWrapper<AgentPlan>()
                .eq(AgentPlan::getStudentId, studentId)
                .eq(AgentPlan::getCourseId, plan.getCourseId())
                .eq(AgentPlan::getStatus, PlanStatus.ACTIVE));
        for (AgentPlan a : actives) {
            a.setStatus(PlanStatus.CANCELLED);
            planMapper.updateById(a);
        }
        plan.setStatus(PlanStatus.ACTIVE);
        plan.setConfirmedAt(now());
        planMapper.updateById(plan);
        return toVo(plan, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanVO cancel(long studentId, long planId) {
        requirePlanEnabled();
        AgentPlan plan = requireOwnedPlan(studentId, planId);
        int st = plan.getStatus() == null ? -1 : plan.getStatus();
        if (st != PlanStatus.DRAFT && st != PlanStatus.ACTIVE) {
            throw new BusinessException(ResultCode.CONFLICT, "仅 DRAFT/ACTIVE 可取消",
                    PlanErrorCodes.PLAN_INVALID_TRANSITION);
        }
        plan.setStatus(PlanStatus.CANCELLED);
        planMapper.updateById(plan);
        return toVo(plan, false);
    }

    @Override
    public PlanVO current(long studentId, long courseId) {
        if (!isPlanEnabled()) {
            return emptyVo(courseId);
        }
        AgentPlan plan = findCurrent(studentId, courseId);
        if (plan == null) {
            return emptyVo(courseId);
        }
        return toVo(plan, true);
    }

    @Override
    public PlanVO progress(long studentId, long courseId) {
        // 读档：无计划返回明确空态，不编造
        return current(studentId, courseId);
    }

    /**
     * 仅 ACTIVE 计划 ID 列表（re-plan / brief 使用；DRAFT 永不进入）。
     */
    public List<AgentPlan> listActivePlans() {
        return planMapper.selectList(new LambdaQueryWrapper<AgentPlan>()
                .eq(AgentPlan::getStatus, PlanStatus.ACTIVE));
    }

    /**
     * DRAFT 是否应被 brief/replan 忽略（硬断言用）。
     */
    public static boolean isDraftIsolated(int status) {
        return status == PlanStatus.DRAFT;
    }

    public PlanAlgorithm.PlanResult runAlgorithm(long studentId, long courseId,
                                                 LocalDate targetDate, List<String> scope) {
        LocalDate today = today();
        List<PlanAlgorithm.NodeSnapshot> snapshots = loadSnapshots(studentId, courseId);
        LimitsProperties.Plan cfg = limits.getPlan();
        return PlanAlgorithm.plan(new PlanAlgorithm.PlanInput(
                today,
                targetDate,
                snapshots,
                scope == null || scope.isEmpty() ? Set.of() : new HashSet<>(scope),
                cfg.getDailyTaskCap(),
                cfg.getWeakThreshold(),
                decayCalculator.getMasteredThreshold(),
                30.0,
                25.0));
    }

    private List<PlanAlgorithm.NodeSnapshot> loadSnapshots(long studentId, long courseId) {
        List<KgNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<KgNode>()
                .eq(KgNode::getCourseId, courseId)
                .isNull(KgNode::getArchivedAt));
        Map<Long, KgNode> byId = nodes.stream()
                .collect(Collectors.toMap(KgNode::getId, n -> n, (a, b) -> a));
        Map<String, KgNode> byCode = nodes.stream()
                .collect(Collectors.toMap(KgNode::getNodeCode, n -> n, (a, b) -> a));

        List<StudentMastery> masteries = masteryMapper.selectList(new LambdaQueryWrapper<StudentMastery>()
                .eq(StudentMastery::getStudentId, studentId)
                .eq(StudentMastery::getCourseId, courseId));
        Map<Long, StudentMastery> masteryByNode = new HashMap<>();
        for (StudentMastery m : masteries) {
            masteryByNode.put(m.getNodeId(), m);
        }

        List<KgEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<KgEdge>()
                .eq(KgEdge::getCourseId, courseId)
                .eq(KgEdge::getRelationType, PrereqDirection.RELATION_TYPE_PREREQUISITE)
                .isNull(KgEdge::getArchivedAt));
        Map<String, List<String>> prereqOf = new HashMap<>();
        for (KgEdge e : edges) {
            if (ReviewMarker.isPending(e.getRelationNote())) {
                continue;
            }
            Long pId = PrereqDirection.prerequisiteNodeId(e);
            Long dId = PrereqDirection.dependentNodeId(e);
            KgNode p = byId.get(pId);
            KgNode d = byId.get(dId);
            if (p == null || d == null) {
                continue;
            }
            prereqOf.computeIfAbsent(d.getNodeCode(), k -> new ArrayList<>()).add(p.getNodeCode());
        }
        Map<String, Integer> topo = PlanAlgorithm.topoIndices(byCode.keySet(), prereqOf);

        List<PlanAlgorithm.NodeSnapshot> out = new ArrayList<>();
        for (KgNode n : nodes) {
            StudentMastery m = masteryByNode.get(n.getId());
            double raw = 0.0;
            double days = 0.0;
            double effective = 0.0;
            if (m != null && m.getMasteryScore() != null) {
                raw = m.getMasteryScore().doubleValue();
                days = decayCalculator.daysSinceEvidence(m.getUpdatedAt());
                effective = decayCalculator.effectiveScoreOf(m, days);
            }
            out.add(new PlanAlgorithm.NodeSnapshot(
                    n.getNodeCode(),
                    effective,
                    raw,
                    days,
                    prereqOf.getOrDefault(n.getNodeCode(), List.of()),
                    topo.getOrDefault(n.getNodeCode(), 0)));
        }
        return out;
    }

    private void cancelExistingDrafts(long studentId, long courseId) {
        List<AgentPlan> drafts = planMapper.selectList(new LambdaQueryWrapper<AgentPlan>()
                .eq(AgentPlan::getStudentId, studentId)
                .eq(AgentPlan::getCourseId, courseId)
                .eq(AgentPlan::getStatus, PlanStatus.DRAFT));
        for (AgentPlan d : drafts) {
            d.setStatus(PlanStatus.CANCELLED);
            planMapper.updateById(d);
        }
    }

    private AgentPlan findCurrent(long studentId, long courseId) {
        AgentPlan active = planMapper.selectOne(new LambdaQueryWrapper<AgentPlan>()
                .eq(AgentPlan::getStudentId, studentId)
                .eq(AgentPlan::getCourseId, courseId)
                .eq(AgentPlan::getStatus, PlanStatus.ACTIVE)
                .last("LIMIT 1"));
        if (active != null) {
            return active;
        }
        return planMapper.selectOne(new LambdaQueryWrapper<AgentPlan>()
                .eq(AgentPlan::getStudentId, studentId)
                .eq(AgentPlan::getCourseId, courseId)
                .eq(AgentPlan::getStatus, PlanStatus.DRAFT)
                .orderByDesc(AgentPlan::getId)
                .last("LIMIT 1"));
    }

    private AgentPlan requireOwnedPlan(long studentId, long planId) {
        AgentPlan plan = planMapper.selectById(planId);
        if (plan == null || !Objects.equals(plan.getStudentId(), studentId)) {
            // 越权与不存在统一 404，避免枚举
            throw new BusinessException(ResultCode.NOT_FOUND, "计划不存在");
        }
        return plan;
    }

    private PlanVO toVo(AgentPlan plan, boolean includeTasks) {
        PlanVO vo = new PlanVO();
        vo.setId(plan.getId());
        vo.setCourseId(plan.getCourseId());
        vo.setStatus(plan.getStatus());
        vo.setTargetDate(plan.getTargetDate());
        vo.setScopeJson(plan.getScopeJson());
        vo.setAtRisk(readAtRisk(plan.getScopeJson()));
        vo.setCreatedAt(plan.getCreatedAt());
        vo.setConfirmedAt(plan.getConfirmedAt());

        List<AgentPlanTask> all = taskMapper.selectList(new LambdaQueryWrapper<AgentPlanTask>()
                .eq(AgentPlanTask::getPlanId, plan.getId())
                .orderByAsc(AgentPlanTask::getTaskDate)
                .orderByAsc(AgentPlanTask::getId));
        int done = 0;
        LocalDate today = today();
        List<PlanTaskVO> todayTasks = new ArrayList<>();
        List<PlanTaskVO> tasks = new ArrayList<>();
        for (AgentPlanTask t : all) {
            if (t.getStatus() != null && t.getStatus() == PlanTaskStatus.DONE) {
                done++;
            }
            PlanTaskVO tv = toTaskVo(t);
            if (includeTasks) {
                tasks.add(tv);
            }
            if (today.equals(t.getTaskDate())
                    && (t.getStatus() == null || t.getStatus() == PlanTaskStatus.PENDING
                    || t.getStatus() == PlanTaskStatus.DONE)) {
                todayTasks.add(tv);
            }
        }
        vo.setTaskCount(all.size());
        vo.setDoneCount(done);
        vo.setTodayTasks(todayTasks);
        vo.setTasks(includeTasks ? tasks : List.of());
        return vo;
    }

    private static PlanTaskVO toTaskVo(AgentPlanTask t) {
        PlanTaskVO tv = new PlanTaskVO();
        tv.setId(t.getId());
        tv.setTaskDate(t.getTaskDate());
        tv.setNodeCode(t.getNodeCode());
        tv.setTaskKind(PlanTaskKind.nameOf(t.getTaskKind() == null ? -1 : t.getTaskKind()));
        tv.setStatus(t.getStatus());
        tv.setOrigin(t.getOrigin());
        return tv;
    }

    private PlanVO emptyVo(long courseId) {
        PlanVO vo = new PlanVO();
        vo.setCourseId(courseId);
        vo.setTaskCount(0);
        vo.setDoneCount(0);
        vo.setTodayTasks(List.of());
        vo.setTasks(List.of());
        vo.setAtRisk(false);
        return vo;
    }

    private List<String> normalizeScope(List<String> scopeNodes, int max) {
        if (scopeNodes == null || scopeNodes.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String s : scopeNodes) {
            if (StringUtils.hasText(s)) {
                set.add(s.trim());
            }
            if (set.size() > max) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "scopeNodes 最多 " + max + " 个");
            }
        }
        return List.copyOf(set);
    }

    private String scopeJson(List<String> scope, boolean atRisk) {
        try {
            Map<String, Object> m = new HashMap<>();
            if (scope != null && !scope.isEmpty()) {
                m.put("nodes", scope);
            }
            if (atRisk) {
                m.put("at_risk", true);
            }
            if (m.isEmpty()) {
                return null;
            }
            return objectMapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    boolean readAtRisk(String scopeJson) {
        if (!StringUtils.hasText(scopeJson)) {
            return false;
        }
        try {
            JsonNode n = objectMapper.readTree(scopeJson);
            return n != null && n.path("at_risk").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    public void markAtRisk(long planId, boolean atRisk) {
        AgentPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            return;
        }
        try {
            Map<String, Object> m = new HashMap<>();
            if (StringUtils.hasText(plan.getScopeJson())) {
                JsonNode n = objectMapper.readTree(plan.getScopeJson());
                if (n != null && n.isObject()) {
                    n.fields().forEachRemaining(e -> m.put(e.getKey(),
                            e.getValue().isBoolean() ? e.getValue().asBoolean()
                                    : (e.getValue().isTextual() ? e.getValue().asText()
                                    : objectMapper.convertValue(e.getValue(), Object.class))));
                }
            }
            if (atRisk) {
                m.put("at_risk", true);
            } else {
                m.remove("at_risk");
            }
            plan.setScopeJson(m.isEmpty() ? null : objectMapper.writeValueAsString(m));
            planMapper.updateById(plan);
        } catch (Exception e) {
            log.warn("markAtRisk failed planId={}: {}", planId, e.toString());
        }
    }

    /**
     * re-plan：昨日及以前 PENDING → RESCHEDULED；重跑算法填充未来（origin=REPLAN）；DONE 不动。
     *
     * @return true 若标记 at_risk
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean replanOne(AgentPlan plan) {
        if (plan == null || plan.getStatus() == null || plan.getStatus() != PlanStatus.ACTIVE) {
            return false;
        }
        LocalDate today = today();
        // 过期 PENDING → RESCHEDULED
        taskMapper.update(null, new LambdaUpdateWrapper<AgentPlanTask>()
                .eq(AgentPlanTask::getPlanId, plan.getId())
                .eq(AgentPlanTask::getStatus, PlanTaskStatus.PENDING)
                .lt(AgentPlanTask::getTaskDate, today)
                .set(AgentPlanTask::getStatus, PlanTaskStatus.RESCHEDULED));

        // 删除未来 PENDING（保留 DONE）
        taskMapper.delete(new LambdaQueryWrapper<AgentPlanTask>()
                .eq(AgentPlanTask::getPlanId, plan.getId())
                .eq(AgentPlanTask::getStatus, PlanTaskStatus.PENDING)
                .ge(AgentPlanTask::getTaskDate, today));

        List<String> scope = readScopeNodes(plan.getScopeJson());
        PlanAlgorithm.PlanResult algo = runAlgorithm(
                plan.getStudentId(), plan.getCourseId(), plan.getTargetDate(), scope);
        boolean atRisk = !algo.ok();
        if (atRisk) {
            markAtRisk(plan.getId(), true);
            // 用建议集截断再排一版（可容纳规模）
            if (!algo.suggestedScope().isEmpty()) {
                algo = runAlgorithm(plan.getStudentId(), plan.getCourseId(),
                        plan.getTargetDate(), algo.suggestedScope());
            }
        }
        if (algo.ok()) {
            for (PlanAlgorithm.PlannedTask t : algo.tasks()) {
                if (t.taskDate().isBefore(today)) {
                    continue;
                }
                AgentPlanTask row = new AgentPlanTask();
                row.setPlanId(plan.getId());
                row.setTaskDate(t.taskDate());
                row.setNodeCode(t.nodeCode());
                row.setTaskKind(t.taskKind());
                row.setStatus(PlanTaskStatus.PENDING);
                row.setOrigin(PlanTaskOrigin.REPLAN);
                taskMapper.insert(row);
            }
        }
        return atRisk;
    }

    private List<String> readScopeNodes(String scopeJson) {
        if (!StringUtils.hasText(scopeJson)) {
            return List.of();
        }
        try {
            JsonNode n = objectMapper.readTree(scopeJson);
            if (n != null && n.has("nodes") && n.get("nodes").isArray()) {
                List<String> out = new ArrayList<>();
                n.get("nodes").forEach(el -> {
                    if (el.isTextual()) {
                        out.add(el.asText());
                    }
                });
                return out;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return List.of();
    }

    private void requirePlanEnabled() {
        if (!isPlanEnabled()) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "学习计划功能未开启",
                    PlanErrorCodes.PLAN_DISABLED);
        }
    }

    private boolean isPlanEnabled() {
        return featureFlags == null || featureFlags.isPlanEnabled();
    }

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
    }
}
