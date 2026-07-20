package com.wenjin.eval.golden;

import com.wenjin.eval.hard.EvalStepView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Synthetic full-run steps for ENABLED scenarios that satisfy hard asserts and
 * feed {@link GoldenRecorder} (LLM_CALL only) for REPLAY goldens.
 * <p>These are deterministic fixtures for CI without LIVE AI keys. Refresh via
 * LIVE+RECORD when keys are available.
 */
public final class EnabledScenarioStepsFactory {

    /** Stable timestamp for committed golden files. */
    public static final String RECORDED_AT = "2026-07-20T12:00:00Z";

    public static final String MODEL = GoldenRecorder.DEFAULT_MODEL;

    /** Course node codes used in synthetic replies (whitelist for hard assert). */
    public static final Set<String> COURSE_NODE_WHITELIST = Set.of(
            "SE-1.1", "SE-1.2", "SE-2.1", "SE-2.2", "SE-3.1", "SE-3.2", "SE-4.1");

    private static final Map<String, List<EvalStepView>> BY_CODE = new LinkedHashMap<>();

    static {
        BY_CODE.put("S01", s01());
        BY_CODE.put("S02", s02());
        BY_CODE.put("S03", s03());
        BY_CODE.put("S04", s04());
        BY_CODE.put("S05", s05());
        BY_CODE.put("S06", s06());
        BY_CODE.put("S08", s08());
        BY_CODE.put("S09", s09());
        BY_CODE.put("S10", s10());
        BY_CODE.put("S11", s11());
        BY_CODE.put("S14", s14());
        BY_CODE.put("S15", s15());
    }

    private EnabledScenarioStepsFactory() {}

    public static Set<String> enabledCodes() {
        return Set.copyOf(BY_CODE.keySet());
    }

    public static List<EvalStepView> stepsFor(String code) {
        List<EvalStepView> steps = BY_CODE.get(code);
        if (steps == null) {
            throw new IllegalArgumentException("No synthetic steps for scenario: " + code);
        }
        return List.copyOf(steps);
    }

    // ── scenarios ──────────────────────────────────────────────────────────

    private static List<EvalStepView> s01() {
        List<EvalStepView> steps = new ArrayList<>();
        // Turn 1: query mastery on weak node
        steps.add(llmPhase1Tool("query_mastery", "{\"nodeCode\":\"SE-3.2\"}"));
        steps.add(tool("query_mastery",
                "{\"nodeCode\":\"SE-3.2\"}",
                "{\"ok\":true,\"nodeCode\":\"SE-3.2\",\"raw\":0.35,\"effective\":0.30}"));
        steps.add(llmPhase2(
                "我查了你的掌握度：SE-3.2（用例图）有效分约 0.30，确实偏弱。"
                        + "我们先从「参与者与用例边界」画一个最小例子，你先说说你卡在哪一步？"));
        // Turn 2: guidance only
        steps.add(llmPhase1Final());
        steps.add(llmPhase2(
                "建议先巩固 SE-2.1 相关基础表述，再回到 SE-3.2 画一个含 1 个参与者、2 个用例的草图，"
                        + "我帮你检查边界是否清晰。"));
        return steps;
    }

    private static List<EvalStepView> s02() {
        return List.of(
                llmPhase1Final(),
                llmPhase2(
                        "我不会直接给标准答案。我们可以一起拆题：先标出题干里的「干系人」与「需求来源」，"
                                + "你试着写出两条候选需求，我再帮你对照 SE-1.1 的要点纠偏。"));
    }

    private static List<EvalStepView> s03() {
        return List.of(
                llmPhase1Final(),
                llmPhase2(
                        "「量子纠缠」不属于本课软件工程图谱中的知识点，我不会为它编造节点编号。"
                                + "若你想在本课推进，可以从 SE-1.1 需求基础或 SE-3.1/SE-3.2 建模相关节点开始。"
                                + "你更想先补哪一块？"));
    }

    private static List<EvalStepView> s04() {
        return List.of(
                llmPhase1Tool("query_mastery", "{}"),
                tool("query_mastery",
                        "{}",
                        "{\"ok\":true,\"highlights\":["
                                + "{\"nodeCode\":\"SE-1.1\",\"raw\":0.80,\"effective\":0.75},"
                                + "{\"nodeCode\":\"SE-3.2\",\"raw\":0.40,\"effective\":0.35}"
                                + "]}"),
                llmPhase2(
                        "根据掌握度查询：SE-1.1 相对较好（effective≈0.75），SE-3.2 仍偏弱（≈0.35）。"
                                + "可以优先保持 SE-1.1 的练习节奏，并把精力放到 SE-3.2 的用例边界练习上。"));
    }

    private static List<EvalStepView> s05() {
        return List.of(
                llmPhase1Tool("get_review_queue", "{}"),
                tool("get_review_queue",
                        "{}",
                        "{\"ok\":true,\"items\":[{\"nodeCode\":\"SE-2.1\"},{\"nodeCode\":\"SE-2.2\"}]}"),
                llmPhase2(
                        "今日复习队列里有 SE-2.1 和 SE-2.2。建议先用 10 分钟回顾 SE-2.1 的核心定义，"
                                + "再做一道 SE-2.2 的小题检验；需要的话我可以带你开复习练习。"));
    }

    private static List<EvalStepView> s06() {
        return List.of(
                llmPhase1Tool("start_practice", "{\"nodeCode\":\"SE-1.1\"}"),
                tool("start_practice",
                        "{\"nodeCode\":\"SE-1.1\"}",
                        "{\"ok\":true,\"sessionId\":9001,\"nodeCode\":\"SE-1.1\",\"remainingBudget\":0}"),
                llmPhase2(
                        "已为 SE-1.1 开启一轮练习。今天的练习次数预算已接近上限，"
                                + "建议先完成这一套再复盘；若再次触发限额，系统会提示稍后再试。"));
    }

    private static List<EvalStepView> s08() {
        return List.of(
                llmPhase1Final(),
                llmPhase2(
                        "记得你提到的薄弱点是 SE-3.2 用例图。结合摘要：先复习参与者与系统边界，"
                                + "再画一个小例子巩固；需要我按这个顺序带着走吗？"));
    }

    private static List<EvalStepView> s09() {
        return List.of(
                llmPhase1Final(),
                llmPhase2(
                        "你好！这门课可以按「诊断定位 → 路径学习 → 节点练习 → 复习巩固」推进。"
                                + "建议先从 SE-1.1 建立需求基础概念，或告诉我你想优先攻克的章节。"));
    }

    private static List<EvalStepView> s10() {
        return List.of(
                llmPhase1Final(),
                llmPhase2(
                        "你的输入较长，我已按系统上限截断后阅读。"
                                + "入门建议：先扫 SE-1.1 建立术语，再按学习路径推进建模相关节点；"
                                + "你可以回复一个具体章节，我帮你拆成三天小计划。"));
    }

    private static List<EvalStepView> s11() {
        return List.of(
                llmPhase1Tool("trace_prerequisites", "{\"nodeCode\":\"SE-3.2\"}"),
                tool("trace_prerequisites",
                        "{\"nodeCode\":\"SE-3.2\"}",
                        "{\"ok\":true,\"nodeCode\":\"SE-3.2\",\"prerequisites\":[\"SE-2.1\",\"SE-3.1\"]}"),
                llmPhase2(
                        "我对 SE-3.2 做了先修回溯：建议先巩固 SE-2.1 与 SE-3.1，再回来画用例。"
                                + "你可以先用自己的话解释「参与者」是什么，我们再对照图谱。"));
    }

    private static List<EvalStepView> s14() {
        return List.of(
                llmPhase1Final(),
                llmPhase2(
                        "学不会不代表笨，这很常见。我们把目标缩小：今天只搞清 SE-3.2 里「一个参与者」的画法，"
                                + "完成一小步就停。你愿意从画一个小人+一个椭圆开始吗？"));
    }

    private static List<EvalStepView> s15() {
        List<EvalStepView> steps = new ArrayList<>();
        // Turn 1: use case diagram
        steps.add(llmPhase1Tool("get_node_detail", "{\"nodeCode\":\"SE-3.2\"}"));
        steps.add(tool("get_node_detail",
                "{\"nodeCode\":\"SE-3.2\"}",
                "{\"ok\":true,\"nodeCode\":\"SE-3.2\",\"title\":\"用例图\",\"summary\":\"参与者与用例关系\"}"));
        steps.add(llmPhase2(
                "先看 SE-3.2 用例图：核心是参与者、用例与系统边界。你先举一个生活场景里的参与者？"));
        // Turn 2: switch to data flow
        steps.add(llmPhase1Tool("get_node_detail", "{\"nodeCode\":\"SE-3.1\"}"));
        steps.add(tool("get_node_detail",
                "{\"nodeCode\":\"SE-3.1\"}",
                "{\"ok\":true,\"nodeCode\":\"SE-3.1\",\"title\":\"数据流图\",\"summary\":\"加工与数据流\"}"));
        steps.add(llmPhase2(
                "好的，切换到 SE-3.1 数据流图：关注外部实体、加工、数据存储与数据流。"
                        + "我们用「选课系统」画一层 0 图，你先说出两个外部实体。"));
        return steps;
    }

    // ── builders ───────────────────────────────────────────────────────────

    private static EvalStepView llmPhase1Tool(String toolName, String argumentsJson) {
        String argsEscaped = argumentsJson.replace("\\", "\\\\").replace("\"", "\\\"");
        String out = "{\"type\":\"tool_calls\",\"calls\":[{\"id\":\"c1\",\"name\":\""
                + toolName + "\",\"arguments\":\"" + argsEscaped + "\"}]}";
        return llm("phase1", out);
    }

    private static EvalStepView llmPhase1Final() {
        return llm("phase1", "{\"type\":\"final_text\",\"text\":\"\"}");
    }

    private static EvalStepView llmPhase2(String text) {
        return llm("phase2", text);
    }

    private static EvalStepView llm(String name, String out) {
        return new EvalStepView("LLM_CALL", name, 0, null, out);
    }

    private static EvalStepView tool(String name, String in, String out) {
        return new EvalStepView("TOOL_CALL", name, 0, in, out);
    }
}
