package com.wenjin.service.companion;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.ProbeStartVO;
import com.wenjin.dto.ProbeSubmitVO;
import com.wenjin.service.memory.StudentMemoryService;
import com.wenjin.support.CompanionIntent;
import com.wenjin.support.MemoryKind;
import com.wenjin.support.Phase1Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Reflection skeleton (M9): after EXPLAIN phase2, optionally issue a probe when
 * {@code reflection-enabled}; on probe submit, expose re-explain + strategy switch
 * and write procedural memory lines.
 */
@Component
public class ReflectionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ReflectionCoordinator.class);

    private final FeatureFlagsProperties featureFlags;
    private final ProbeService probeService;
    /** 探针结果与策略状态；null 时不做换策略注入（兼容旧构造）。 */
    private final ProbeOutcomeStore outcomeStore;

    @Autowired(required = false)
    private StudentMemoryService memoryService;

    public ReflectionCoordinator(FeatureFlagsProperties featureFlags, ProbeService probeService) {
        this(featureFlags, probeService, null);
    }

    @Autowired
    public ReflectionCoordinator(FeatureFlagsProperties featureFlags,
                                 ProbeService probeService,
                                 ProbeOutcomeStore outcomeStore) {
        this.featureFlags = featureFlags;
        this.probeService = probeService;
        this.outcomeStore = outcomeStore;
    }

    /**
     * 本 turn 的换策略指令。
     *
     * @param nodeCode         需重讲的节点（最近未通过探针的节点）
     * @param strategy         新选用的策略
     * @param previousStrategy 上次讲解所用策略（null = 默认讲法）
     */
    public record StrategyDirective(String nodeCode,
                                    StrategySwitcher.Strategy strategy,
                                    StrategySwitcher.Strategy previousStrategy) {
    }

    /**
     * 组装 system prompt 前调用：若学生在焦点节点（或最近一次探针节点）上有 30 分钟内未通过、且尚未据此换过
     * 策略的探针，则经 {@link #nextStrategyOnIncorrect} 选下一个策略、把上次策略记为无效并标记已切换。
     * 最近一次探针已通过且其策略尚未记录 → 记为有效，不注入。{@code reflection-enabled=false} → 不注入、不改状态。
     */
    public Optional<StrategyDirective> resolveStrategyDirective(Long studentId, Long courseId, String focusNodeCode) {
        if (!isReflectionEnabled() || outcomeStore == null || studentId == null || courseId == null) {
            return Optional.empty();
        }
        Optional<ProbeOutcomeStore.NodeOutcome> latest = outcomeStore.latest(studentId, courseId, focusNodeCode);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        ProbeOutcomeStore.NodeOutcome outcome = latest.get();
        String node = outcome.nodeCode();
        if (outcome.correct()) {
            if (outcome.strategyUsed() != null && !outcome.outcomeRecorded()) {
                recordStrategyOutcome(studentId, courseId, outcome.strategyUsed(), true,
                        "节点 " + node + " 改用该讲法后理解探针通过");
                outcomeStore.markOutcomeRecorded(studentId, courseId, node);
            }
            return Optional.empty();
        }
        if (outcome.switchConsumed() || !outcomeStore.isWithinWindow(outcome)) {
            return Optional.empty();
        }
        if (outcome.strategyUsed() != null && !outcome.outcomeRecorded()) {
            recordStrategyOutcome(studentId, courseId, outcome.strategyUsed(), false,
                    "节点 " + node + " 用该讲法后理解探针仍未通过");
            outcomeStore.markOutcomeRecorded(studentId, courseId, node);
        }
        ProbeSubmitVO failed = new ProbeSubmitVO();
        failed.setCorrect(false);
        failed.setReexplainAvailable(true);
        failed.setNodeCode(node);
        Optional<StrategySwitcher.Strategy> next = nextStrategyOnIncorrect(failed, outcome.tried(), Set.of());
        outcomeStore.markStrategySwitched(studentId, courseId, node, next.orElse(null));
        return next.map(s -> new StrategyDirective(node, s, outcome.strategyUsed()));
    }

    public boolean isReflectionEnabled() {
        return featureFlags == null || featureFlags.isReflectionEnabled();
    }

    /**
     * Whether to auto-issue a probe after phase2 for this decision.
     * Only EXPLAIN + flag on; QUERY/PRACTICE/CHAT → no auto-probe.
     */
    public boolean shouldAutoProbe(Phase1Decision decision) {
        if (!isReflectionEnabled() || decision == null) {
            return false;
        }
        return decision.intent() == CompanionIntent.EXPLAIN;
    }

    public boolean shouldAutoProbe(CompanionIntent intent) {
        if (!isReflectionEnabled() || intent == null) {
            return false;
        }
        return intent == CompanionIntent.EXPLAIN;
    }

    /**
     * Issue a probe for the focus node (if present). Failures are swallowed → empty.
     */
    public Optional<ProbeStartVO> maybeIssueProbe(Long studentId,
                                                  Long courseId,
                                                  String nodeCode,
                                                  Phase1Decision decision) {
        if (!shouldAutoProbe(decision) || !StringUtils.hasText(nodeCode)) {
            return Optional.empty();
        }
        try {
            return Optional.of(probeService.startProbe(studentId, courseId, nodeCode.trim()));
        } catch (Exception e) {
            log.warn("auto probe failed studentId={} courseId={} node={}: {}",
                    studentId, courseId, nodeCode, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * After probe submit: pick next strategy when incorrect.
     */
    public Optional<StrategySwitcher.Strategy> nextStrategyOnIncorrect(
            ProbeSubmitVO submitResult,
            Set<StrategySwitcher.Strategy> used,
            Set<StrategySwitcher.Strategy> invalid) {
        if (submitResult == null || submitResult.isCorrect()) {
            return Optional.empty();
        }
        if (!submitResult.isReexplainAvailable()) {
            return Optional.empty();
        }
        return StrategySwitcher.next(used, invalid);
    }

    /**
     * Write procedural memory line for strategy effectiveness.
     * Format: {@code [STRATEGY][有效/无效] ...}
     */
    public void recordStrategyOutcome(Long studentId,
                                      Long courseId,
                                      StrategySwitcher.Strategy strategy,
                                      boolean effective,
                                      String detail) {
        if (memoryService == null || strategy == null || studentId == null || courseId == null) {
            return;
        }
        if (featureFlags != null && !featureFlags.isMemoryEnabled()) {
            return;
        }
        try {
            String content = StrategySwitcher.proceduralMemoryLine(strategy, effective, detail);
            memoryService.upsertOrReinforce(
                    studentId,
                    courseId,
                    MemoryKind.PROCEDURAL,
                    content,
                    null,
                    null,
                    BigDecimal.ONE);
        } catch (Exception e) {
            log.warn("procedural memory write failed: {}", e.getMessage());
        }
    }

    /** Empty used set helper. */
    public static Set<StrategySwitcher.Strategy> emptyUsed() {
        return EnumSet.noneOf(StrategySwitcher.Strategy.class);
    }
}
