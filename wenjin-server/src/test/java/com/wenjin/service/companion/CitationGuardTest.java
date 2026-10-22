package com.wenjin.service.companion;

import com.wenjin.service.trace.StepRecord;
import com.wenjin.support.AgentStepType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CitationGuardTest {

    @Test
    void citableSetIsUnionOfInjectedAndSearchMaterialsResults() {
        CitationGuard guard = new CitationGuard(Set.of(1L, 2L));
        guard.addToolResult("search_materials",
                "{\"items\":[{\"chunkId\":7,\"snippet\":\"a\",\"sourceTitle\":\"t\"},{\"chunkId\":8}],\"count\":2}");
        guard.addToolResult("search_materials", "{\"items\":[{\"chunkId\":7}],\"count\":1}");
        assertThat(guard.citable()).containsExactlyInAnyOrder(1L, 2L, 7L, 8L);
    }

    @Test
    void otherToolsAndMalformedContentAreIgnored() {
        CitationGuard guard = new CitationGuard(Set.of());
        guard.addToolResult("query_mastery", "{\"items\":[{\"chunkId\":5}]}");
        guard.addToolResult("search_materials", "not json");
        guard.addToolResult("search_materials", null);
        guard.addToolResult("search_materials", "{\"ok\":false,\"code\":\"TOOL_NOT_ALLOWED\"}");
        guard.addToolResult("search_materials", "{\"items\":[{\"chunkId\":\"x\"},{\"snippet\":\"no id\"}]}");
        assertThat(guard.citable()).isEmpty();
    }

    @Test
    void nullInjectedMeansEmptySet() {
        assertThat(new CitationGuard(null).citable()).isEmpty();
    }

    @Test
    void extractIdsKeepsOrderAndDedupes() {
        assertThat(CitationGuard.extractIds("A 资料 #3，B 资料#12。C 资料   #3 D 资料 # 4"))
                .containsExactly(3L, 12L);
        assertThat(CitationGuard.extractIds(null)).isEmpty();
        assertThat(CitationGuard.extractIds("没有引用 #5")).isEmpty();
    }

    @Test
    void checkSplitsValidAndInvalidAndRewritesInvalidOnly() {
        CitationGuard guard = new CitationGuard(Set.of(10L, 11L));
        CitationGuard.Verdict v = guard.check("用例图描述参与者。资料 #10 数据流图见资料 #99。再见资料 #11。");
        assertThat(v.hasCitations()).isTrue();
        assertThat(v.valid()).containsExactly(10L, 11L);
        assertThat(v.invalid()).containsExactly(99L);
        assertThat(v.text()).isEqualTo("用例图描述参与者。资料 #10 数据流图见（引用无效）。再见资料 #11。");
        assertThat(v.rewritten()).isTrue();
    }

    @Test
    void checkAllValidLeavesTextUntouched() {
        CitationGuard guard = new CitationGuard(Set.of(1L));
        String text = "要点如下 资料 #1";
        CitationGuard.Verdict v = guard.check(text);
        assertThat(v.valid()).containsExactly(1L);
        assertThat(v.invalid()).isEmpty();
        assertThat(v.text()).isSameAs(text);
        assertThat(v.rewritten()).isFalse();
    }

    @Test
    void checkWithNoCitationsHasNoCitations() {
        CitationGuard.Verdict v = new CitationGuard(Set.of(1L)).check("课程资料未覆盖这个问题。");
        assertThat(v.hasCitations()).isFalse();
        assertThat(v.valid()).isEmpty();
        assertThat(v.invalid()).isEmpty();
    }

    @Test
    void emptyCitableSetInvalidatesEveryCitation() {
        CitationGuard.Verdict v = new CitationGuard(Set.of()).check("这是编造的 资料 #5 与 资料 #5。");
        assertThat(v.invalid()).containsExactly(5L);
        assertThat(v.text()).isEqualTo("这是编造的 （引用无效） 与 （引用无效）。");
    }

    @Test
    void overflowingIdIsRewrittenAsInvalid() {
        CitationGuard.Verdict v = new CitationGuard(Set.of(1L)).check("见资料 #99999999999999999999999 与资料 #1");
        assertThat(v.text()).isEqualTo("见（引用无效） 与资料 #1");
        assertThat(v.valid()).containsExactly(1L);
        assertThat(v.hasCitations()).isTrue();
    }

    @Test
    void neutralizeRewritesWithoutNeedingVerdict() {
        CitationGuard guard = new CitationGuard(Set.of(1L));
        assertThat(guard.neutralize("资料 #1 资料 #2")).isEqualTo("资料 #1 （引用无效）");
        assertThat(guard.neutralize(null)).isNull();
    }

    @Test
    void stepRecordCarriesValidAndInvalidInOutput() {
        CitationGuard guard = new CitationGuard(Set.of(10L));
        StepRecord step = guard.toStep(guard.check("资料 #10 资料 #99"), 3);
        assertThat(step.stepType()).isEqualTo(AgentStepType.CITATION);
        assertThat(step.name()).isEqualTo("citation_check");
        assertThat(step.outputJson()).isEqualTo("{\"valid\":[10],\"invalid\":[99]}");
        assertThat(step.inputJson()).isEqualTo("{\"cited\":[10,99],\"citable\":[10]}");
        assertThat(step.durationMs()).isEqualTo(3);
        assertThat(List.of(step.tokensIn(), step.tokensOut())).containsExactly(0, 0);
    }

    @Test
    void truncatedSearchMaterialsJsonFallsBackToRegexIds() {
        CitationGuard guard = new CitationGuard(Set.of());
        // 被截断的 JSON（尾部缺括号 / 最后一项残缺）
        guard.addToolResult("search_materials",
                "{\"items\":[{\"chunkId\":31,\"snippet\":\"a\"},{\"chunkId\": 32,\"snippet\":\"bb\"},{\"chunkId\":3");
        assertThat(guard.citable()).contains(31L, 32L);
    }

    @Test
    void overflowCitationIsReportedInvalidInStepOutput() {
        CitationGuard guard = new CitationGuard(Set.of(1L));
        StepRecord step = guard.toStep(guard.check("见资料 #99999999999999999999999 与资料 #1"), 1);
        assertThat(step.outputJson()).isEqualTo("{\"valid\":[1],\"invalid\":[-1]}");
    }
}
