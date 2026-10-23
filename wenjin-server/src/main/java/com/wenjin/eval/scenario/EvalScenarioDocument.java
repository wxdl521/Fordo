package com.wenjin.eval.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Locale;
import java.util.Set;

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
        LiveFaults liveFaults,
        /** 可选：对话发起方，{@code student}（缺省，学习伴侣）或 {@code teacher}（教师 AI 助教） */
        String actor
) {
    public static final String ACTOR_STUDENT = "student";
    public static final String ACTOR_TEACHER = "teacher";

    /** 兼容旧调用方（无 liveFaults / actor）。 */
    public EvalScenarioDocument(String code, String title, Persona persona, List<ScriptTurn> script,
                                HardAssertions hard, JudgeSpec judge, String status, String pendingReason) {
        this(code, title, persona, script, hard, judge, status, pendingReason, null, null);
    }

    /** 兼容旧调用方（无 actor）。 */
    public EvalScenarioDocument(String code, String title, Persona persona, List<ScriptTurn> script,
                                HardAssertions hard, JudgeSpec judge, String status, String pendingReason,
                                LiveFaults liveFaults) {
        this(code, title, persona, script, hard, judge, status, pendingReason, liveFaults, null);
    }

    /** 对话发起方；缺省 student。 */
    public String resolvedActor() {
        return actor == null || actor.isBlank() ? ACTOR_STUDENT : actor.trim().toLowerCase(Locale.ROOT);
    }

    public boolean forTeacher() {
        return ACTOR_TEACHER.equals(resolvedActor());
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
     * @param roster   可选，仅 {@code actor=teacher}：选课花名册（1–5 名）。教师场景下评测学生本身被移出选课，
     *                 名册即全班。
     */
    public record Persona(
            List<MasteryOverride> masteryOverrides,
            List<String> reviewQueue,
            String note,
            List<MistakeSeed> mistakes,
            List<RosterStudent> roster
    ) {
        /** 兼容旧调用方（无 roster）。 */
        public Persona(List<MasteryOverride> masteryOverrides, List<String> reviewQueue, String note,
                       List<MistakeSeed> mistakes) {
            this(masteryOverrides, reviewQueue, note, mistakes, null);
        }

        /** 兼容旧调用方（无 mistakes / roster）。 */
        public Persona(List<MasteryOverride> masteryOverrides, List<String> reviewQueue, String note) {
            this(masteryOverrides, reviewQueue, note, null, null);
        }
    }

    /**
     * 教师场景的花名册学生（确定性学情画像）。该生所有作答记录落在 {@code lastPracticeDaysAgo} 天前
     * （缺省 1；0 = 刚刚），因此 &ge;14 天触发「久未练习」，&lt;7 天的错题计入「近期错题」。
     *
     * @param name                姓名（sys_user.real_name），名册内唯一
     * @param masteryOverrides    各知识点掌握度（只用 {@code raw}；updated_at=现在，故有效分=raw；不允许 effective）
     * @param mistakes            错题种子（语义同 {@link Persona#mistakes()}）；缺省无
     * @param correctAnswers      额外的正确作答条数（落在第一个掌握度知识点的题上），缺省 0
     * @param lastPracticeDaysAgo 作答发生在多少天前；缺省 1
     * @param snapshots           历史掌握度快照（用于「近 7 天下降」）；缺省无
     * @param otherCourse         true = 该学生只选了另一门课程（不属于本课程，不得出现在本课程的回答里）
     * @param privateChat         该学生与学习伴侣的私密对话原文（教师不得读取），写入 companion_message 作隐私诱饵
     */
    public record RosterStudent(
            String name,
            List<MasteryOverride> masteryOverrides,
            List<MistakeSeed> mistakes,
            Integer correctAnswers,
            Integer lastPracticeDaysAgo,
            List<SnapshotSeed> snapshots,
            Boolean otherCourse,
            String privateChat
    ) {}

    /** 一条历史掌握度快照：{@code daysAgo} 天前该知识点分值为 {@code score}（0–1）。 */
    public record SnapshotSeed(
            String nodeCode,
            Integer daysAgo,
            Double score
    ) {}

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
            Boolean degradedExpected,
            /** true：trace 的 citation_check 步 invalid 必须为空（回复里的 资料 #N ⊆ 本 turn 可引用集合） */
            Boolean citationsValid,
            /** 最终回复不得包含的子串（如学生源码的整行：不回显源码）；逐项精确子串匹配 */
            List<String> replyMustNotContain,
            /** 最终回复必须包含的子串（如教师场景里被点名的高风险学生）；逐项精确子串匹配 */
            List<String> replyMustContain,
            /** GUARD 步必须出现的拦截码（如 TOOL_NOT_ALLOWED），逐项各至少一个 */
            List<String> guardCodesExpected,
            /**
             * 仅 REPLAY 生效的硬断言键（取值为本记录的字段名，如 {@code mustCallTools}）。用于只能由合成 golden
             * 确定性构造的行为（如诱导模型越权调用工具以覆盖 GUARD 拦截）：真模型正确地不去尝试时，LIVE 不应判失败。
             */
            List<String> replayOnly,
            /**
             * 任选其一组：每组（工具名列表）至少成功调用其中一个即通过；多组须全部满足。用于模型可合理走不同取数路径的场景
             * （如掌握度既可 query_mastery 直查、也可经 trace_prerequisites 带出）。
             */
            List<List<String>> mustCallAnyOf
    ) {
        /** {@link #replayOnly} 允许引用的键（= 本记录的断言字段名）。 */
        public static final Set<String> KEYS = Set.of(
                "mustCallTools", "mustNotCallTools", "maxToolCalls", "jsonContractValid", "nodeCodesInWhitelist",
                "degradedExpected", "citationsValid", "replyMustNotContain", "replyMustContain",
                "guardCodesExpected", "mustCallAnyOf");

        /** 兼容旧调用方（无 mustCallAnyOf）。 */
        public HardAssertions(List<String> mustCallTools, List<String> mustNotCallTools, Integer maxToolCalls,
                              Boolean jsonContractValid, Boolean nodeCodesInWhitelist, Boolean degradedExpected,
                              Boolean citationsValid, List<String> replyMustNotContain,
                              List<String> replyMustContain, List<String> guardCodesExpected,
                              List<String> replayOnly) {
            this(mustCallTools, mustNotCallTools, maxToolCalls, jsonContractValid, nodeCodesInWhitelist,
                    degradedExpected, citationsValid, replyMustNotContain, replyMustContain, guardCodesExpected,
                    replayOnly, null);
        }

        /** 兼容旧调用方（无 replayOnly）。 */
        public HardAssertions(List<String> mustCallTools, List<String> mustNotCallTools, Integer maxToolCalls,
                              Boolean jsonContractValid, Boolean nodeCodesInWhitelist, Boolean degradedExpected,
                              Boolean citationsValid, List<String> replyMustNotContain,
                              List<String> replyMustContain, List<String> guardCodesExpected) {
            this(mustCallTools, mustNotCallTools, maxToolCalls, jsonContractValid, nodeCodesInWhitelist,
                    degradedExpected, citationsValid, replyMustNotContain, replyMustContain, guardCodesExpected, null, null);
        }

        /**
         * LIVE 视图：去掉 {@link #replayOnly} 列出的断言（置空 = 不检查）；无 replayOnly 时返回自身。
         */
        public HardAssertions forLive() {
            if (replayOnly == null || replayOnly.isEmpty()) {
                return this;
            }
            Set<String> drop = Set.copyOf(replayOnly);
            return new HardAssertions(
                    drop.contains("mustCallTools") ? null : mustCallTools,
                    drop.contains("mustNotCallTools") ? null : mustNotCallTools,
                    drop.contains("maxToolCalls") ? null : maxToolCalls,
                    drop.contains("jsonContractValid") ? null : jsonContractValid,
                    drop.contains("nodeCodesInWhitelist") ? null : nodeCodesInWhitelist,
                    drop.contains("degradedExpected") ? null : degradedExpected,
                    drop.contains("citationsValid") ? null : citationsValid,
                    drop.contains("replyMustNotContain") ? null : replyMustNotContain,
                    drop.contains("replyMustContain") ? null : replyMustContain,
                    drop.contains("guardCodesExpected") ? null : guardCodesExpected,
                    null,
                    drop.contains("mustCallAnyOf") ? null : mustCallAnyOf);
        }

        /** 按执行模式取生效断言：LIVE 用 {@link #forLive()}，REPLAY 用全部。 */
        public HardAssertions effective(boolean live) {
            return live ? forLive() : this;
        }
        /** 兼容旧调用方（无 replyMustContain / guardCodesExpected）。 */
        public HardAssertions(List<String> mustCallTools, List<String> mustNotCallTools, Integer maxToolCalls,
                              Boolean jsonContractValid, Boolean nodeCodesInWhitelist, Boolean degradedExpected,
                              Boolean citationsValid, List<String> replyMustNotContain) {
            this(mustCallTools, mustNotCallTools, maxToolCalls, jsonContractValid, nodeCodesInWhitelist,
                    degradedExpected, citationsValid, replyMustNotContain, null, null, null, null);
        }

        /** 兼容旧调用方（无 citationsValid / replyMustNotContain）。 */
        public HardAssertions(List<String> mustCallTools, List<String> mustNotCallTools, Integer maxToolCalls,
                              Boolean jsonContractValid, Boolean nodeCodesInWhitelist, Boolean degradedExpected) {
            this(mustCallTools, mustNotCallTools, maxToolCalls, jsonContractValid, nodeCodesInWhitelist,
                    degradedExpected, null, null, null, null, null, null);
        }

        /** 兼容旧调用方（无 replyMustNotContain）。 */
        public HardAssertions(List<String> mustCallTools, List<String> mustNotCallTools, Integer maxToolCalls,
                              Boolean jsonContractValid, Boolean nodeCodesInWhitelist, Boolean degradedExpected,
                              Boolean citationsValid) {
            this(mustCallTools, mustNotCallTools, maxToolCalls, jsonContractValid, nodeCodesInWhitelist,
                    degradedExpected, citationsValid, null, null, null, null, null);
        }
    }

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
