package com.wenjin.eval.collect;

import com.wenjin.dto.PracticeStartVO;

import java.util.List;
import java.util.Objects;

/**
 * Synchronously collected companion stream outcome (SSE → one value).
 */
public record CollectedReply(
        String text,
        List<PracticeStartVO> practiceCards,
        List<ToolCallEvent> toolEvents
) {
    public CollectedReply {
        text = text == null ? "" : text;
        practiceCards = practiceCards == null ? List.of() : List.copyOf(practiceCards);
        toolEvents = toolEvents == null ? List.of() : List.copyOf(toolEvents);
    }

    public boolean isBlank() {
        return text.isBlank();
    }

    public List<String> toolNamesCalled() {
        return toolEvents.stream()
                .map(ToolCallEvent::name)
                .filter(Objects::nonNull)
                .toList();
    }

    public record ToolCallEvent(String name, String label, boolean ok) {}
}
