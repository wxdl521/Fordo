package com.wenjin.service.multiagent;

import com.wenjin.entity.AgentRun;
import com.wenjin.eval.judge.JudgeRequest;
import com.wenjin.eval.judge.JudgeResult;
import com.wenjin.eval.judge.JudgeScore;
import com.wenjin.eval.judge.JudgeService;
import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeDimension;
import com.wenjin.support.AgentRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Function;

/**
 * 伴学质量抽查：随机抽样 COMPANION_CHAT run（排除 DEGRADED/CANCELLED），
 * 复用 {@link JudgeService} 打分，低分写入 flags。
 * <p>Random 可注入以保证单测确定性。
 */
public final class SupervisorSampler {

    private static final Logger log = LoggerFactory.getLogger(SupervisorSampler.class);

    public static final String KIND_COMPANION_CHAT = "COMPANION_CHAT";

    /** 默认维度阈值：任一 score &lt; 此值 → flag。 */
    public static final int DEFAULT_LOW_SCORE_THRESHOLD = 3;

    private final JudgeService judgeService;
    private final Random random;
    private final int lowScoreThreshold;
    private final List<JudgeDimension> dimensions;

    public SupervisorSampler(JudgeService judgeService, Random random) {
        this(judgeService, random, DEFAULT_LOW_SCORE_THRESHOLD, defaultDimensions());
    }

    public SupervisorSampler(JudgeService judgeService,
                             Random random,
                             int lowScoreThreshold,
                             List<JudgeDimension> dimensions) {
        this.judgeService = Objects.requireNonNull(judgeService, "judgeService");
        this.random = random == null ? new Random() : random;
        this.lowScoreThreshold = Math.max(1, lowScoreThreshold);
        this.dimensions = dimensions == null || dimensions.isEmpty()
                ? defaultDimensions()
                : List.copyOf(dimensions);
    }

    public static List<JudgeDimension> defaultDimensions() {
        return List.of(
                new JudgeDimension("helpfulness", "帮助学生理解", 3),
                new JudgeDimension("correctness", "知识正确性", 3),
                new JudgeDimension("safety", "安全与边界", 3)
        );
    }

    /**
     * 过滤可抽样 run：kind=COMPANION_CHAT，排除 DEGRADED/CANCELLED。
     */
    public static List<AgentRun> eligible(List<AgentRun> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<AgentRun> out = new ArrayList<>();
        for (AgentRun r : candidates) {
            if (r == null || r.getId() == null) {
                continue;
            }
            if (!KIND_COMPANION_CHAT.equals(r.getKind())) {
                continue;
            }
            Integer st = r.getStatus();
            if (st != null && (st == AgentRunStatus.DEGRADED || st == AgentRunStatus.CANCELLED)) {
                continue;
            }
            out.add(r);
        }
        return out;
    }

    /**
     * 从 eligible 集合随机抽 N 个（不足则全取；顺序打乱可复现）。
     */
    public List<AgentRun> sample(List<AgentRun> candidates, int n) {
        List<AgentRun> pool = new ArrayList<>(eligible(candidates));
        if (pool.isEmpty() || n <= 0) {
            return List.of();
        }
        Collections.shuffle(pool, random);
        if (pool.size() <= n) {
            return List.copyOf(pool);
        }
        return List.copyOf(pool.subList(0, n));
    }

    /**
     * 对抽样 run 打分并收集低分 flags。
     *
     * @param sampled            已抽样 run
     * @param transcriptForRun   runId → 转写（消息摘要）
     * @param toolSummaryForRun  runId → 工具摘要
     * @return payload 片段：sampledRunIds / flags / judgePromptVersion
     */
    public Map<String, Object> evaluate(
            List<AgentRun> sampled,
            Function<Long, String> transcriptForRun,
            Function<Long, String> toolSummaryForRun
    ) {
        List<Long> ids = new ArrayList<>();
        List<Map<String, Object>> flags = new ArrayList<>();
        String promptVersion = judgeService.promptVersion();

        if (sampled != null) {
            for (AgentRun run : sampled) {
                if (run == null || run.getId() == null) {
                    continue;
                }
                ids.add(run.getId());
                String transcript = transcriptForRun == null
                        ? ""
                        : nullToEmpty(transcriptForRun.apply(run.getId()));
                String tools = toolSummaryForRun == null
                        ? ""
                        : nullToEmpty(toolSummaryForRun.apply(run.getId()));
                JudgeRequest req = new JudgeRequest(transcript, tools, "supervisor-sample", dimensions);
                JudgeResult result;
                try {
                    result = judgeService.judge(req);
                } catch (Exception e) {
                    log.warn("supervisor judge failed runId={}: {}", run.getId(), e.toString());
                    continue;
                }
                if (!result.success()) {
                    log.debug("supervisor judge contract fail runId={}: {}",
                            run.getId(), result.failureReason());
                    continue;
                }
                for (JudgeScore score : result.scores()) {
                    if (score == null) {
                        continue;
                    }
                    if (score.score() < lowScoreThreshold) {
                        Map<String, Object> flag = new LinkedHashMap<>();
                        flag.put("runId", run.getId());
                        flag.put("dimension", score.key());
                        flag.put("score", score.score());
                        flag.put("reason", score.reason() == null ? "" : score.reason());
                        flags.add(flag);
                    }
                }
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sampledRunIds", ids);
        payload.put("flags", flags);
        payload.put("judgePromptVersion", promptVersion);
        payload.put("lowScoreThreshold", lowScoreThreshold);
        return payload;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
