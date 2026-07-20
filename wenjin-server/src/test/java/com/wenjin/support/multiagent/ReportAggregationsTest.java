package com.wenjin.support.multiagent;

import com.wenjin.support.multiagent.ReportAggregations.Anomaly;
import com.wenjin.support.multiagent.ReportAggregations.AnomalyType;
import com.wenjin.support.multiagent.ReportAggregations.EffectiveDelta;
import com.wenjin.support.multiagent.ReportAggregations.Engagement;
import com.wenjin.support.multiagent.ReportAggregations.NodeStudentEffective;
import com.wenjin.support.multiagent.ReportAggregations.StudentLastPractice;
import com.wenjin.support.multiagent.ReportAggregations.WeakNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 周报聚合纯函数：表驱动夹具。
 */
class ReportAggregationsTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 7, 20);

    // ─── 班级弱点 ───────────────────────────────────────────────

    static Stream<Arguments> weakNodeCases() {
        return Stream.of(
                Arguments.of(
                        "lowest 5 of 7 by avg",
                        List.of(
                                nse(1, "N1", 0.90), nse(2, "N1", 0.80), // avg 0.85
                                nse(1, "N2", 0.10), nse(2, "N2", 0.20), // 0.15
                                nse(1, "N3", 0.50),                     // 0.50
                                nse(1, "N4", 0.30), nse(2, "N4", 0.40), // 0.35
                                nse(1, "N5", 0.05),                     // 0.05
                                nse(1, "N6", 0.25),                     // 0.25
                                nse(1, "N7", 0.60)                      // 0.60
                        ),
                        5,
                        List.of("N5", "N2", "N6", "N4", "N3")
                ),
                Arguments.of(
                        "tie break by nodeCode",
                        List.of(
                                nse(1, "B", 0.20),
                                nse(1, "A", 0.20),
                                nse(1, "C", 0.50)
                        ),
                        2,
                        List.of("A", "B")
                ),
                Arguments.of(
                        "empty input",
                        List.of(),
                        5,
                        List.of()
                ),
                Arguments.of(
                        "topN larger than nodes",
                        List.of(nse(1, "X", 0.4), nse(1, "Y", 0.2)),
                        5,
                        List.of("Y", "X")
                )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("weakNodeCases")
    @DisplayName("weakNodes: lowest N by avg effective")
    void weakNodes_tableDriven(String name,
                               List<NodeStudentEffective> rows,
                               int topN,
                               List<String> expectedCodes) {
        List<WeakNode> weak = ReportAggregations.weakNodes(rows, topN);
        assertThat(weak).extracting(WeakNode::nodeCode).containsExactlyElementsOf(expectedCodes);
    }

    // ─── 异常 ───────────────────────────────────────────────────

    static Stream<Arguments> anomalyCases() {
        return Stream.of(
                Arguments.of(
                        "drop > 0.15 flagged",
                        List.of(new EffectiveDelta(10L, "N1", 0.80, 0.60)), // drop 0.20
                        List.of(),
                        1,
                        AnomalyType.EFFECTIVE_DROP
                ),
                Arguments.of(
                        "drop at/under 0.15 not flagged (strict >)",
                        List.of(new EffectiveDelta(10L, "N1", 0.80, 0.66)), // drop 0.14
                        List.of(),
                        0,
                        null
                ),
                Arguments.of(
                        "drop < 0.15 not flagged",
                        List.of(new EffectiveDelta(10L, "N1", 0.50, 0.40)), // 0.10
                        List.of(),
                        0,
                        null
                ),
                Arguments.of(
                        "14d no practice",
                        List.of(),
                        List.of(new StudentLastPractice(20L, AS_OF.minusDays(14))),
                        1,
                        AnomalyType.NO_PRACTICE
                ),
                Arguments.of(
                        "13d still ok",
                        List.of(),
                        List.of(new StudentLastPractice(21L, AS_OF.minusDays(13))),
                        0,
                        null
                ),
                Arguments.of(
                        "never practiced",
                        List.of(),
                        List.of(new StudentLastPractice(22L, null)),
                        1,
                        AnomalyType.NO_PRACTICE
                )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("anomalyCases")
    @DisplayName("anomalies: drop threshold and no-practice window")
    void anomalies_tableDriven(String name,
                               List<EffectiveDelta> deltas,
                               List<StudentLastPractice> practices,
                               int expectedCount,
                               AnomalyType expectedType) {
        List<Anomaly> out = ReportAggregations.anomalies(deltas, practices, AS_OF);
        assertThat(out).hasSize(expectedCount);
        if (expectedCount > 0) {
            assertThat(out.get(0).type()).isEqualTo(expectedType);
        }
    }

    @Test
    @DisplayName("anomalies combine drop + no practice, stable order")
    void anomalies_combined() {
        List<Anomaly> out = ReportAggregations.anomalies(
                List.of(
                        new EffectiveDelta(2L, "Z", 0.9, 0.5),
                        new EffectiveDelta(1L, "A", 0.7, 0.4)
                ),
                List.of(new StudentLastPractice(3L, null)),
                AS_OF
        );
        assertThat(out).hasSize(3);
        // type name order: EFFECTIVE_DROP before NO_PRACTICE
        assertThat(out.get(0).type()).isEqualTo(AnomalyType.EFFECTIVE_DROP);
        assertThat(out.get(0).studentId()).isEqualTo(1L);
        assertThat(out.get(1).studentId()).isEqualTo(2L);
        assertThat(out.get(2).type()).isEqualTo(AnomalyType.NO_PRACTICE);
    }

    // ─── 参与度环比 ─────────────────────────────────────────────

    static Stream<Arguments> engagementCases() {
        return Stream.of(
                Arguments.of(10, 5, 2.0, 4, 2, 2.0),
                Arguments.of(0, 0, 1.0, 0, 0, 1.0),
                Arguments.of(3, 0, Double.POSITIVE_INFINITY, 0, 5, 0.0),
                Arguments.of(8, 4, 2.0, 9, 3, 3.0)
        );
    }

    @ParameterizedTest(name = "d {0}/{1}={2} p {3}/{4}={5}")
    @MethodSource("engagementCases")
    @DisplayName("engagement: WoW ratio")
    void engagement_tableDriven(int dThis, int dLast, double dRatio,
                                int pThis, int pLast, double pRatio) {
        Engagement e = ReportAggregations.engagement(dThis, dLast, pThis, pLast);
        assertThat(e.dialogueThisWeek()).isEqualTo(dThis);
        assertThat(e.dialogueLastWeek()).isEqualTo(dLast);
        assertThat(e.dialogueWowRatio()).isEqualTo(dRatio);
        assertThat(e.practiceThisWeek()).isEqualTo(pThis);
        assertThat(e.practiceLastWeek()).isEqualTo(pLast);
        assertThat(e.practiceWowRatio()).isEqualTo(pRatio);
    }

    @Test
    @DisplayName("engagementAsMap maps infinity ratio to null")
    void engagementAsMap_infinityBecomesNull() {
        Engagement e = ReportAggregations.engagement(2, 0, 0, 0);
        var map = ReportAggregations.engagementAsMap(e);
        assertThat(map.get("dialogueWowRatio")).isNull();
        assertThat(map.get("practiceWowRatio")).isEqualTo(1.0);
    }

    private static NodeStudentEffective nse(long studentId, String node, double eff) {
        return new NodeStudentEffective(studentId, node, eff);
    }
}
