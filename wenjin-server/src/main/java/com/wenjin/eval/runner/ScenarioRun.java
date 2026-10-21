package com.wenjin.eval.runner;

import java.util.List;
import java.util.Objects;

/**
 * A scenario execution with its conversation transcript (for the judge and calibration export).
 */
public record ScenarioRun(EvalCaseOutcome outcome, List<Exchange> exchanges) {

    public ScenarioRun {
        Objects.requireNonNull(outcome, "outcome");
        exchanges = exchanges == null ? List.of() : List.copyOf(exchanges);
    }

    /** One user message and the companion's streamed reply. */
    public record Exchange(String user, String reply, List<String> tools) {
        public Exchange {
            user = user == null ? "" : user;
            reply = reply == null ? "" : reply;
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    /** Plain-text transcript: one "学生/伴侣" block per exchange, tools in between. */
    public String transcript() {
        StringBuilder sb = new StringBuilder();
        for (Exchange e : exchanges) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("学生：").append(e.user()).append('\n');
            if (!e.tools().isEmpty()) {
                sb.append("（工具：").append(String.join(", ", e.tools())).append("）\n");
            }
            sb.append("伴侣：").append(e.reply());
        }
        return sb.toString();
    }
}
