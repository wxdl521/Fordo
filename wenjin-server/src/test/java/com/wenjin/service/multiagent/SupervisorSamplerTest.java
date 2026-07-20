package com.wenjin.service.multiagent;

import com.wenjin.entity.AgentRun;
import com.wenjin.eval.judge.JudgeAiClient;
import com.wenjin.eval.judge.JudgeService;
import com.wenjin.support.AgentRunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class SupervisorSamplerTest {

    @Test
    @DisplayName("eligible excludes DEGRADED/CANCELLED and non-COMPANION_CHAT")
    void eligible_filters() {
        List<AgentRun> pool = List.of(
                run(1L, "COMPANION_CHAT", AgentRunStatus.SUCCEEDED),
                run(2L, "COMPANION_CHAT", AgentRunStatus.DEGRADED),
                run(3L, "COMPANION_CHAT", AgentRunStatus.CANCELLED),
                run(4L, "DIAGNOSTIC", AgentRunStatus.SUCCEEDED),
                run(5L, "COMPANION_CHAT", AgentRunStatus.FAILED),
                run(6L, "COMPANION_CHAT", AgentRunStatus.RUNNING)
        );
        List<AgentRun> elig = SupervisorSampler.eligible(pool);
        assertThat(elig).extracting(AgentRun::getId).containsExactly(1L, 5L, 6L);
    }

    @Test
    @DisplayName("sample is deterministic with fixed Random seed")
    void sample_injectableRandom() {
        List<AgentRun> pool = List.of(
                run(1L, "COMPANION_CHAT", AgentRunStatus.SUCCEEDED),
                run(2L, "COMPANION_CHAT", AgentRunStatus.SUCCEEDED),
                run(3L, "COMPANION_CHAT", AgentRunStatus.SUCCEEDED),
                run(4L, "COMPANION_CHAT", AgentRunStatus.SUCCEEDED)
        );
        JudgeService judge = new JudgeService(fixedJudgeClient(5));
        SupervisorSampler s1 = new SupervisorSampler(judge, new Random(42L));
        SupervisorSampler s2 = new SupervisorSampler(judge, new Random(42L));
        List<Long> a = s1.sample(pool, 2).stream().map(AgentRun::getId).toList();
        List<Long> b = s2.sample(pool, 2).stream().map(AgentRun::getId).toList();
        assertThat(a).isEqualTo(b).hasSize(2);
    }

    @Test
    @DisplayName("evaluate flags dimensions below threshold; reuses judge prompt version")
    void evaluate_flagsLowScores() {
        JudgeService judge = new JudgeService(fixedJudgeClient(2));
        SupervisorSampler sampler = new SupervisorSampler(judge, new Random(1L));
        AgentRun run = run(99L, "COMPANION_CHAT", AgentRunStatus.SUCCEEDED);
        Map<String, Object> payload = sampler.evaluate(
                List.of(run),
                id -> "student asked about SE-1.1",
                id -> "query_mastery");

        assertThat(payload.get("judgePromptVersion")).isEqualTo(JudgeService.PROMPT_VERSION);
        assertThat(payload.get("sampledRunIds")).isEqualTo(List.of(99L));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flags = (List<Map<String, Object>>) payload.get("flags");
        assertThat(flags).isNotEmpty();
        assertThat(flags.get(0).get("runId")).isEqualTo(99L);
        assertThat(flags.get(0).get("score")).isEqualTo(2);
        assertThat(flags).allMatch(f -> (int) f.get("score") < SupervisorSampler.DEFAULT_LOW_SCORE_THRESHOLD);
    }

    @Test
    @DisplayName("evaluate high scores produce empty flags")
    void evaluate_highScoresNoFlags() {
        JudgeService judge = new JudgeService(fixedJudgeClient(5));
        SupervisorSampler sampler = new SupervisorSampler(judge, new Random(1L));
        Map<String, Object> payload = sampler.evaluate(
                List.of(run(1L, "COMPANION_CHAT", AgentRunStatus.SUCCEEDED)),
                id -> "ok",
                id -> "");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flags = (List<Map<String, Object>>) payload.get("flags");
        assertThat(flags).isEmpty();
    }

    private static JudgeAiClient fixedJudgeClient(int score) {
        String json = """
                {"scores":[
                  {"key":"helpfulness","score":%d,"reason":"r1"},
                  {"key":"correctness","score":%d,"reason":"r2"},
                  {"key":"safety","score":%d,"reason":"r3"}
                ]}
                """.formatted(score, score, score);
        return prompt -> json;
    }

    private static AgentRun run(long id, String kind, int status) {
        AgentRun r = new AgentRun();
        r.setId(id);
        r.setKind(kind);
        r.setStatus(status);
        r.setCourseId(1L);
        return r;
    }
}
