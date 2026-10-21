package com.wenjin.service.companion;

import com.wenjin.service.companion.StrategySwitcher.Strategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 理解探针结果与讲解策略状态（键：学生 × 课程 × 节点，保留最近一次）。
 *
 * <p>供「换个方式再讲」接入真实链路：探针提交时记录对错与当时生效的讲解策略；下一 turn 组装 system prompt
 * 时由 {@link ReflectionCoordinator#resolveStrategyDirective} 读取，30 分钟内未通过且尚未换过策略 →
 * 选下一个策略注入提示。
 *
 * <p><b>刻意不持久化</b>：与 {@link ProbeSessionStore} 一致为进程内状态（探针会话本身也不落库），
 * 重启后丢失只意味着下一次讲解回到默认讲法，不影响掌握度 / 证据；容量超限时淘汰一天前的条目。
 */
@Component
public class ProbeOutcomeStore {

    /** 「最近一次未通过的探针」时间窗。 */
    public static final Duration FAILED_PROBE_WINDOW = Duration.ofMinutes(30);
    static final int PRUNE_THRESHOLD = 10_000;
    static final Duration RETENTION = Duration.ofDays(1);

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

    private record Key(Long studentId, Long courseId, String nodeCode) {
    }

    private final ConcurrentHashMap<Key, NodeOutcome> outcomes = new ConcurrentHashMap<>();
    private final Clock clock;

    public ProbeOutcomeStore() {
        this(Clock.systemUTC());
    }

    @Autowired
    public ProbeOutcomeStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 探针提交后调用（只在首次判分时）。 */
    public void recordProbeResult(Long studentId, Long courseId, String nodeCode, boolean correct) {
        if (studentId == null || courseId == null || !StringUtils.hasText(nodeCode)) {
            return;
        }
        Key key = new Key(studentId, courseId, nodeCode.trim());
        outcomes.compute(key, (k, prev) -> {
            Strategy used = prev == null ? null : prev.activeStrategy();
            Set<Strategy> tried = correct || prev == null ? Set.of() : prev.tried();
            return new NodeOutcome(k.nodeCode(), correct, clock.instant(), used, used, tried, false, false);
        });
        prune();
    }

    /** 焦点节点有记录优先，否则该学生本课最近一次探针。 */
    public Optional<NodeOutcome> latest(Long studentId, Long courseId, String focusNodeCode) {
        if (studentId == null || courseId == null) {
            return Optional.empty();
        }
        if (StringUtils.hasText(focusNodeCode)) {
            NodeOutcome focus = outcomes.get(new Key(studentId, courseId, focusNodeCode.trim()));
            if (focus != null) {
                return Optional.of(focus);
            }
        }
        return outcomes.entrySet().stream()
                .filter(e -> studentId.equals(e.getKey().studentId()) && courseId.equals(e.getKey().courseId()))
                .map(java.util.Map.Entry::getValue)
                .max(Comparator.comparing(NodeOutcome::at));
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
        return outcomes.size();
    }

    public void clear() {
        outcomes.clear();
    }

    private void update(Long studentId, Long courseId, String nodeCode,
                        java.util.function.UnaryOperator<NodeOutcome> fn) {
        if (studentId == null || courseId == null || !StringUtils.hasText(nodeCode)) {
            return;
        }
        outcomes.computeIfPresent(new Key(studentId, courseId, nodeCode.trim()), (k, o) -> fn.apply(o));
    }

    private void prune() {
        if (outcomes.size() <= PRUNE_THRESHOLD) {
            return;
        }
        Instant cutoff = clock.instant().minus(RETENTION);
        outcomes.values().removeIf(o -> o.at() == null || o.at().isBefore(cutoff));
    }
}
