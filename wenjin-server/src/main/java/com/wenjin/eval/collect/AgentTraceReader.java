package com.wenjin.eval.collect;

import com.wenjin.eval.hard.EvalStepView;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads the agent trace ({@code agent_run} / {@code agent_step}) written by one companion turn back as
 * {@link EvalStepView}s with metrics, so hard asserts run against what production actually recorded.
 */
public final class AgentTraceReader {

    private final JdbcTemplate jdbc;

    public AgentTraceReader(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Highest run id of a conversation so far (0 when none) — take it before a turn. */
    public long maxRunId(long conversationId) {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM agent_run WHERE conversation_id = ?",
                Long.class, conversationId);
        return max == null ? 0L : max;
    }

    /** Run ids of a conversation created after {@code afterRunId}, ascending. */
    public List<Long> runIdsAfter(long conversationId, long afterRunId) {
        return jdbc.queryForList(
                "SELECT id FROM agent_run WHERE conversation_id = ? AND id > ? ORDER BY id",
                Long.class, conversationId, afterRunId);
    }

    /** Steps of the given runs, ordered by run then seq. */
    public List<EvalStepView> steps(List<Long> runIds) {
        List<EvalStepView> out = new ArrayList<>();
        if (runIds == null) {
            return out;
        }
        for (Long runId : runIds) {
            if (runId == null) {
                continue;
            }
            out.addAll(jdbc.query(
                    "SELECT step_type, name, status, input_json, output_json, seq, tokens_in, tokens_out,"
                            + " duration_ms, error_code FROM agent_step WHERE run_id = ? ORDER BY seq",
                    (rs, i) -> toView(rs), runId));
        }
        return out;
    }

    static EvalStepView toView(ResultSet rs) throws SQLException {
        return toView(
                rs.getString("step_type"),
                rs.getString("name"),
                rs.getInt("status"),
                rs.getString("input_json"),
                rs.getString("output_json"),
                nullableInt(rs, "seq"),
                nullableInt(rs, "tokens_in"),
                nullableInt(rs, "tokens_out"),
                nullableLong(rs, "duration_ms"),
                rs.getString("error_code"));
    }

    /** Pure mapping of one {@code agent_step} row. */
    public static EvalStepView toView(String stepType, String name, int status, String input, String output,
                                      Integer seq, Integer tokensIn, Integer tokensOut, Long durationMs,
                                      String errorCode) {
        return new EvalStepView(stepType, name, status, input, output, seq, tokensIn, tokensOut, durationMs,
                errorCode);
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}
