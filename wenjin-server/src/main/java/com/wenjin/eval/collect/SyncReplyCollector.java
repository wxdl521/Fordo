package com.wenjin.eval.collect;

import com.wenjin.dto.PracticeStartVO;
import com.wenjin.eval.collect.CollectedReply.ToolCallEvent;
import com.wenjin.service.CompanionService;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects {@link CompanionService.CompanionStreamSink} tokens/events into a final synchronous result.
 * <p>No Spring dependency — unit-testable pure component.
 */
public final class SyncReplyCollector extends CompanionService.CompanionStreamSink {

    private final StringBuilder text = new StringBuilder();
    private final List<PracticeStartVO> practiceCards = new ArrayList<>();
    private final List<ToolCallEvent> toolEvents = new ArrayList<>();
    private final List<String> pendingToolLabels = new ArrayList<>();

    @Override
    public void token(String token) {
        if (token != null) {
            text.append(token);
        }
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
