package com.wenjin.service.companion;

import com.wenjin.service.companion.StrategySwitcher.Strategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 理解探针结果与讲解策略状态（键：学生 × 课程 × 节点，保留最近一次）。
 *
 * <p>供「换个方式再讲」接入真实链路：探针提交时记录对错与当时生效的讲解策略；下一 turn 组装 system prompt
 * 时由 {@link ReflectionCoordinator#resolveStrategyDirective} 读取，30 分钟内未通过且尚未换过策略 →
 * 选下一个策略注入提示。
 *
 * <p>状态落库于 {@code probe_outcome}（V26），重启 / 部署后 30 分钟窗口内的「换个方式再讲」不丢；
 * 仍与掌握度 / 证据隔离（不写 student_mastery / answer_record）。过期行由 {@link ProbeStateCleanup} 清理。
 */
@Component
public class ProbeOutcomeStore {

    /** 「最近一次未通过的探针」时间窗。 */
    public static final Duration FAILED_PROBE_WINDOW = Duration.ofMinutes(30);

    /**
     * @param correct         最近一次探针是否通过
     * @param at              提交时间
     * @param strategyUsed    该探针所检验的讲解所用策略（null = 默认讲法）
     * @param activeStrategy  当前生效（最近一次换上）的策略；下一次探针提交时成为 strategyUsed
     * @param tried           本轮反思循环中已用过的策略（通过后清空）
     * @param switchConsumed  这次未通过已触发过一次换策略（防止同一失败反复切换）
     * @param outcomeRecorded strategyUsed 的有效 / 无效已写入程序性记忆
     */
    public record NodeOutcome(String nodeCode,
                              boolean correct,
                              Instant at,
                              Strategy strategyUsed,
                              Strategy activeStrategy,
                              Set<Strategy> tried,
                              boolean switchConsumed,
                              boolean outcomeRecorded) {
        public NodeOutcome {
            tried = tried == null || tried.isEmpty()
                    ? Set.of()
                    : Set.copyOf(EnumSet.copyOf(tried));
        }
    }

    private final ProbeOutcomeRepository repository;
    private final Clock clock;

    /** Memory-only (unit tests without DB). */
    public ProbeOutcomeStore() {
        this(new InMemoryProbeOutcomeRepository(), Clock.systemUTC());
    }

    /** Memory-only with explicit clock (unit tests). */
    public ProbeOutcomeStore(Clock clock) {
        this(new InMemoryProbeOutcomeRepository(), clock);
    }

    @Autowired
    public ProbeOutcomeStore(ProbeOutcomeRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 探针提交后调用（只在首次判分时）。 */
    public void recordProbeResult(Long studentId, Long courseId, String nodeCode, boolean correct) {
        if (studentId == null || courseId == null || !StringUtils.hasText(nodeCode)) {
            return;
        }
        String code = nodeCode.trim();
        NodeOutcome prev = repository.find(studentId, courseId, code).orElse(null);
        Strategy used = prev == null ? null : prev.activeStrategy();
        Set<Strategy> tried = correct || prev == null ? Set.of() : prev.tried();
        repository.save(studentId, courseId,
                new NodeOutcome(code, correct, clock.instant(), used, used, tried, false, false));
    }

    /** 焦点节点有记录优先，否则该学生本课最近一次探针。 */
    public Optional<NodeOutcome> latest(Long studentId, Long courseId, String focusNodeCode) {
        if (studentId == null || courseId == null) {
            return Optional.empty();
        }
        if (StringUtils.hasText(focusNodeCode)) {
            Optional<NodeOutcome> focus = repository.find(studentId, courseId, focusNodeCode.trim());
            if (focus.isPresent()) {
                return focus;
            }
        }
        return repository.findMostRecent(studentId, courseId);
    }

    public boolean isWithinWindow(NodeOutcome outcome) {
        return outcome != null && outcome.at() != null
                && !outcome.at().isBefore(clock.instant().minus(FAILED_PROBE_WINDOW));
    }

    /** 已为这次未通过换上新策略。 */
    public void markStrategySwitched(Long studentId, Long courseId, String nodeCode, Strategy next) {
        update(studentId, courseId, nodeCode, o -> {
            EnumSet<Strategy> tried = o.tried().isEmpty() ? EnumSet.noneOf(Strategy.class) : EnumSet.copyOf(o.tried());
            if (next != null) {
                tried.add(next);
            }
            return new NodeOutcome(o.nodeCode(), o.correct(), o.at(), o.strategyUsed(),
                    next == null ? o.activeStrategy() : next, tried, true, o.outcomeRecorded());
        });
    }

    public void markOutcomeRecorded(Long studentId, Long courseId, String nodeCode) {
        update(studentId, courseId, nodeCode, o -> new NodeOutcome(o.nodeCode(), o.correct(), o.at(),
                o.strategyUsed(), o.activeStrategy(), o.tried(), o.switchConsumed(), true));
    }

    public int size() {
        return (int) repository.count();
    }

    public void clear() {
        repository.deleteAll();
    }

    /** Purge outcomes whose probe time is before {@code cutoff}; returns rows removed. */
    public int purgeBefore(Instant cutoff) {
        return repository.deleteOlderThan(cutoff);
    }

    private void update(Long studentId, Long courseId, String nodeCode,
                        java.util.function.UnaryOperator<NodeOutcome> fn) {
        if (studentId == null || courseId == null || !StringUtils.hasText(nodeCode)) {
            return;
        }
        String code = nodeCode.trim();
        repository.find(studentId, courseId, code)
                .ifPresent(o -> repository.save(studentId, courseId, fn.apply(o)));
    }
}
