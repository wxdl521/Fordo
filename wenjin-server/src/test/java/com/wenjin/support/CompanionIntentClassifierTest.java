package com.wenjin.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static com.wenjin.support.CompanionIntent.CHAT;
import static com.wenjin.support.CompanionIntent.EXPLAIN;
import static com.wenjin.support.CompanionIntent.PRACTICE;
import static com.wenjin.support.CompanionIntent.QUERY;
import static org.assertj.core.api.Assertions.assertThat;

class CompanionIntentClassifierTest {

    /** Expected intent for the first user message of each eval scenario (derived from title/purpose and §2.3). */
    private static final Map<String, CompanionIntent> SCENARIO_EXPECTED = new LinkedHashMap<>();

    static {
        SCENARIO_EXPECTED.put("S01", QUERY);   // mustCall query_mastery; rule priority gives QUERY (title hints EXPLAIN)
        SCENARIO_EXPECTED.put("S02", CHAT);    // asks for the standard answer, no direct answer
        SCENARIO_EXPECTED.put("S03", CHAT);    // off-graph question, no explanation keyword, no tools
        SCENARIO_EXPECTED.put("S04", QUERY);   // mustCall query_mastery
        SCENARIO_EXPECTED.put("S05", QUERY);   // mustCall get_review_queue
        SCENARIO_EXPECTED.put("S06", PRACTICE); // mustCall start_practice
        SCENARIO_EXPECTED.put("S07", CHAT);    // no mustCall; rules give CHAT (title: tool failure path)
        SCENARIO_EXPECTED.put("S08", CHAT);    // recall/review chat, no explanation keyword
        SCENARIO_EXPECTED.put("S09", CHAT);    // greeting-style first message
        SCENARIO_EXPECTED.put("S10", EXPLAIN); // "请从基础概念讲起" is an explanation request
        SCENARIO_EXPECTED.put("S11", EXPLAIN); // mustCall trace_prerequisites
        SCENARIO_EXPECTED.put("S12", EXPLAIN); // "讲一下"
        SCENARIO_EXPECTED.put("S13", EXPLAIN); // mustCall get_node_detail, "讲一下"
        SCENARIO_EXPECTED.put("S14", CHAT);    // emotional
        SCENARIO_EXPECTED.put("S15", EXPLAIN); // "讲一下" + get_node_detail
        SCENARIO_EXPECTED.put("S16", CHAT);    // injection probe, no tools
        SCENARIO_EXPECTED.put("S17", CHAT);    // casual chat
        SCENARIO_EXPECTED.put("S18", CHAT);    // memory recall, no practice tool
        SCENARIO_EXPECTED.put("S19", EXPLAIN); // mustCall search_materials
        SCENARIO_EXPECTED.put("S20", QUERY);   // mustCall draft_study_plan
        SCENARIO_EXPECTED.put("S21", QUERY);   // mustCall get_plan_progress
    }

    static Stream<Arguments> scenarioFirstMessages() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<Arguments> rows = new ArrayList<>();
        for (Map.Entry<String, CompanionIntent> entry : SCENARIO_EXPECTED.entrySet()) {
            String path = "/eval/scenarios/" + entry.getKey() + ".json";
            try (InputStream in = CompanionIntentClassifierTest.class.getResourceAsStream(path)) {
                Objects.requireNonNull(in, "missing scenario resource " + path);
                JsonNode root = mapper.readTree(in);
                String firstMessage = root.path("script").get(0).path("user").asText();
                List<String> mustCall = new ArrayList<>();
                root.path("hard").path("mustCallTools").forEach(node -> mustCall.add(node.asText()));
                rows.add(Arguments.of(entry.getKey(), firstMessage, mustCall, entry.getValue()));
            }
        }
        assertThat(rows).as("all 21 scenarios covered").hasSize(21);
        return rows.stream();
    }

    @ParameterizedTest(name = "{0}: first message -> {3}")
    @MethodSource("scenarioFirstMessages")
    void classifiesScenarioFirstMessage(String scenarioId, String firstMessage,
                                        List<String> mustCallTools, CompanionIntent expected) {
        assertThat(CompanionIntentClassifier.classify(firstMessage, mustCallTools))
                .as(scenarioId)
                .isEqualTo(expected);
    }

    @Test
    void practiceToolsGivePractice() {
        assertThat(CompanionIntentClassifier.classify("来一题", List.of("start_practice"))).isEqualTo(PRACTICE);
        assertThat(CompanionIntentClassifier.classify("考考我", List.of("probe_understanding"))).isEqualTo(PRACTICE);
    }

    @Test
    void practiceBeatsQueryAndExplain() {
        assertThat(CompanionIntentClassifier.classify("讲一下并查查进度",
                List.of("query_mastery", "start_practice", "get_node_detail"))).isEqualTo(PRACTICE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft_study_plan", "get_plan_progress", "query_mastery", "get_review_queue"})
    void pureQueryToolsGiveQuery(String tool) {
        assertThat(CompanionIntentClassifier.classify("查一下", List.of(tool))).isEqualTo(QUERY);
    }

    @Test
    void queryBeatsExplainKeywordWhenNoExplainTool() {
        assertThat(CompanionIntentClassifier.classify("什么是掌握度？", List.of("query_mastery"))).isEqualTo(QUERY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"get_node_detail", "search_materials", "trace_prerequisites", "get_recent_mistakes"})
    void explainToolsGiveExplain(String tool) {
        assertThat(CompanionIntentClassifier.classify("这个怎么样", List.of(tool))).isEqualTo(EXPLAIN);
    }

    @Test
    void explainToolWithQueryToolIsNotPureQuery() {
        assertThat(CompanionIntentClassifier.classify("看看进度", List.of("query_mastery", "get_node_detail")))
                .isEqualTo(EXPLAIN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"讲讲用例图", "讲一下用例图", "讲起来听听", "请讲解一下", "解释一下", "什么是用例",
            "用例图是什么", "为什么会这样", "怎么理解这个", "两者的区别", "这个原理", "举例说明"})
    void explanationKeywordsGiveExplain(String message) {
        assertThat(CompanionIntentClassifier.classify(message, List.of())).isEqualTo(EXPLAIN);
    }

    @Test
    void answerSeekingOverridesExplainKeyword() {
        assertThat(CompanionIntentClassifier.classify("直接告诉我这道题的标准答案是什么", List.of())).isEqualTo(CHAT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"你好", "谢谢你", "今天好累", "随便聊聊", "我学了好久还是不会，好崩溃"})
    void smallTalkAndEmotionGiveChat(String message) {
        assertThat(CompanionIntentClassifier.classify(message, List.of())).isEqualTo(CHAT);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void blankMessageWithoutToolsGivesChat(String message) {
        assertThat(CompanionIntentClassifier.classify(message, List.of())).isEqualTo(CHAT);
    }

    @Test
    void nullToolCollectionIsTolerated() {
        assertThat(CompanionIntentClassifier.classify("讲一下", null)).isEqualTo(EXPLAIN);
        assertThat(CompanionIntentClassifier.classify(null, null)).isEqualTo(CHAT);
    }

    @Test
    void unknownAndNullToolNamesAreIgnored() {
        Collection<String> tools = java.util.Arrays.asList(null, "unknown_tool");
        assertThat(CompanionIntentClassifier.classify("你好", tools)).isEqualTo(CHAT);
    }

    @Test
    void nullMessageWithExplainToolStillExplains() {
        assertThat(CompanionIntentClassifier.classify(null, List.of("get_node_detail"))).isEqualTo(EXPLAIN);
    }
}
