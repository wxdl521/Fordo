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
        String pendingReason
) {
    public record Persona(
            List<MasteryOverride> masteryOverrides,
            List<String> reviewQueue,
            String note
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
