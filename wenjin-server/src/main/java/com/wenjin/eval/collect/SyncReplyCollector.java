package com.wenjin.eval.collect;

import com.wenjin.dto.PracticeStartVO;
import com.wenjin.eval.collect.CollectedReply.ToolCallEvent;
import com.wenjin.service.CompanionService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Collects {@link CompanionService.CompanionStreamSink} tokens/events into a final synchronous result.
 * <p>Also measures time-to-first-token for eval metrics. No Spring dependency — unit-testable pure component.
 */
public final class SyncReplyCollector extends CompanionService.CompanionStreamSink {

    private final StringBuilder text = new StringBuilder();
    private final List<PracticeStartVO> practiceCards = new ArrayList<>();
    private final List<ToolCallEvent> toolEvents = new ArrayList<>();
    private final List<String> pendingToolLabels = new ArrayList<>();
    private final LongSupplier nanoClock;
    private final long startedNanos;
    private volatile long firstTokenNanos = -1;

    public SyncReplyCollector() {
        this(System::nanoTime);
    }

    /** @param nanoClock monotonic clock (injectable for tests); the collector starts timing on construction */
    public SyncReplyCollector(LongSupplier nanoClock) {
        this.nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
        this.startedNanos = this.nanoClock.getAsLong();
    }

    @Override
    public void token(String token) {
        if (token != null) {
            if (firstTokenNanos < 0 && !token.isEmpty()) {
                firstTokenNanos = nanoClock.getAsLong();
            }
            text.append(token);
        }
    }

    /** Time from construction to the first non-empty token (ms); null when no token arrived. */
    public Long ttftMs() {
        long first = firstTokenNanos;
        return first < 0 ? null : Math.max(0L, (first - startedNanos) / 1_000_000L);
    }

    /** Time from construction until now (ms). */
    public long elapsedMs() {
        return Math.max(0L, (nanoClock.getAsLong() - startedNanos) / 1_000_000L);
    }

    @Override
    public void toolStart(String name, String label) {
        pendingToolLabels.add(label == null ? "" : label);
        // provisional; finalized on toolResult
        toolEvents.add(new ToolCallEvent(name, label, true));
    }

    @Override
    public void toolResult(String name, boolean ok) {
        if (toolEvents.isEmpty()) {
            toolEvents.add(new ToolCallEvent(name, null, ok));
            return;
        }
        // Update last matching start if name matches, else append
        for (int i = toolEvents.size() - 1; i >= 0; i--) {
            ToolCallEvent e = toolEvents.get(i);
            if (ObjectsEquals(e.name(), name)) {
                toolEvents.set(i, new ToolCallEvent(name, e.label(), ok));
                return;
            }
        }
        toolEvents.add(new ToolCallEvent(name, null, ok));
    }

    @Override
    public void practiceCard(PracticeStartVO card) {
        if (card != null) {
            practiceCards.add(card);
        }
    }

    /** Snapshot of collected reply so far (or final). */
    public CollectedReply result() {
        return new CollectedReply(text.toString(), practiceCards, toolEvents);
    }

    public String text() {
        return text.toString();
    }

    public List<PracticeStartVO> practiceCards() {
        return List.copyOf(practiceCards);
    }

    private static boolean ObjectsEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
