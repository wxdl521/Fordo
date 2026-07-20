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

    @Autowired(required = false)
    private StudentMemoryService memoryService;

    public ReflectionCoordinator(FeatureFlagsProperties featureFlags, ProbeService probeService) {
        this.featureFlags = featureFlags;
        this.probeService = probeService;
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
