package com.wenjin.eval.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 评测场景 JSON 文档（classpath {@code eval/scenarios/*.json} 为唯一事实源）。
 * <p>与 M6 场景 schema 对齐；DB 表 {@code eval_scenario} 由加载器同步，不手工编辑。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record EvalScenarioDocument(
        String code,
        String title,
        Persona persona,
        List<ScriptTurn> script,
        HardAssertions hard,
        JudgeSpec judge,
        /** ENABLED / DISABLED / PENDING；缺省 ENABLED */
        String status,
        /** status=PENDING 时的原因，如 M9-reflection */
        String pendingReason,
        /** 可选：LIVE/REPLAY 故障注入（真模型下无法自然复现的场景，如降级、预算耗尽） */
        LiveFaults liveFaults
) {
    /** 兼容旧调用方（无 liveFaults）。 */
    public EvalScenarioDocument(String code, String title, Persona persona, List<ScriptTurn> script,
                                HardAssertions hard, JudgeSpec judge, String status, String pendingReason) {
        this(code, title, persona, script, hard, judge, status, pendingReason, null);
    }

    /**
     * 故障注入：{@code llmFailures} = 本场景前 N 次 LLM 调用按供应商失败抛出（触发降级）；
     * {@code maxToolCalls} = 本场景工具预算覆盖 {@code wenjin.companion.tools.max-tool-calls}（触发预算耗尽）。
     */
    public record LiveFaults(
            Integer llmFailures,
            Integer maxToolCalls
    ) {}

    /**
     * @param mistakes 可选：近期错题（错因辅导场景）；夹具为每条在 {@code nodeCode} 的一道评测题上写入一次
     *                 错误作答，所选错误选项标注 {@code misconceptionNodeCode} 为误区节点。
     */
    public record Persona(
            List<MasteryOverride> masteryOverrides,
            List<String> reviewQueue,
            String note,
            List<MistakeSeed> mistakes
    ) {
        /** 兼容旧调用方（无 mistakes）。 */
        public Persona(List<MasteryOverride> masteryOverrides, List<String> reviewQueue, String note) {
            this(masteryOverrides, reviewQueue, note, null);
        }
    }

    /** 一道错题种子：在 nodeCode 的题上选错，所选干扰项指向 misconceptionNodeCode。 */
    public record MistakeSeed(
            String nodeCode,
            String misconceptionNodeCode
    ) {}

    public record MasteryOverride(
            String nodeCode,
            Double raw,
            Double effective
    ) {}

    public record ScriptTurn(String user) {}

    public record HardAssertions(
            List<String> mustCallTools,
            List<String> mustNotCallTools,
            Integer maxToolCalls,
            Boolean jsonContractValid,
            Boolean nodeCodesInWhitelist,
            Boolean degradedExpected
    ) {}

    public record JudgeSpec(List<JudgeDimension> dimensions) {}

    public record JudgeDimension(
            String key,
            String desc,
            Integer threshold
    ) {}

    public EvalScenarioStatus resolvedStatus() {
        return EvalScenarioStatus.fromName(status);
    }
}
