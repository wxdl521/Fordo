package com.wenjin.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AdaptiveDiagState JSON 序列化往返测试（M2 T3 TDD）。
 *
 * <p>覆盖简报 4 项测试：
 * <ol>
 *   <li>非平凡状态往返 + Map{@literal <Long,NodeState>} 键类型显式断言（Long 非 String）；</li>
 *   <li>currentProbe=null 往返（终止态会话）；</li>
 *   <li>空 nodeStates / 空 probeHistory 边界往返；</li>
 *   <li>畸形 JSON 抛 {@link IllegalStateException}。</li>
 * </ol>
 */
class AdaptiveDiagStateTest {

    @Test
    @DisplayName("1. 非平凡状态往返：≥3节点不同状态 + budgetUsed>0 + currentProbe 非空 + probeHistory≥2；Map键必须是Long")
    void roundTrip_nonTrivial_mapKeyIsLong() {
        // 5 个节点，覆盖全部状态枚举值
        Map<Long, NodeState> states = Map.of(
                10L, NodeState.TESTED_PASS,
                20L, NodeState.TESTED_FAIL,
                30L, NodeState.INFERRED_PASS,
                40L, NodeState.INFERRED_FAIL,
                50L, NodeState.UNKNOWN
        );
        AdaptiveDiagState.Probe probe = new AdaptiveDiagState.Probe(99L, List.of(201L, 202L));
        List<AdaptiveDiagState.ProbeRecord> history = List.of(
                new AdaptiveDiagState.ProbeRecord(10L, List.of(101L, 102L), true),
                new AdaptiveDiagState.ProbeRecord(20L, List.of(111L, 112L), false)
        );
        AdaptiveDiagState original = new AdaptiveDiagState(states, 4, probe, history);

        String json = original.toJson();
        AdaptiveDiagState restored = AdaptiveDiagState.fromJson(json);

        // 基本字段
        assertThat(restored.budgetUsed()).isEqualTo(4);
        assertThat(restored.nodeStates()).isEqualTo(states);

        // 关键断言：Map 键必须是 Long（Jackson 反序列化 Map key 时的经典坑——
        // 若 ObjectMapper 未正确解析泛型，key 会变成 String，
        // 导致 AdaptiveEngine 恢复构造器收到 Map<String,NodeState> 而非 Map<Long,NodeState>）
        restored.nodeStates().keySet().forEach(key ->
                assertThat(key)
                        .as("Map key 必须是 Long，实际类型: %s", key.getClass().getName())
                        .isInstanceOf(Long.class)
        );

        // currentProbe
        assertThat(restored.currentProbe()).isNotNull();
        assertThat(restored.currentProbe().nodeId()).isEqualTo(99L);
        assertThat(restored.currentProbe().questionIds()).containsExactly(201L, 202L);

        // probeHistory
        assertThat(restored.probeHistory()).hasSize(2);
        assertThat(restored.probeHistory().get(0).nodeId()).isEqualTo(10L);
        assertThat(restored.probeHistory().get(0).questionIds()).containsExactly(101L, 102L);
        assertThat(restored.probeHistory().get(0).pass()).isTrue();
        assertThat(restored.probeHistory().get(1).nodeId()).isEqualTo(20L);
        assertThat(restored.probeHistory().get(1).pass()).isFalse();
    }

    @Test
    @DisplayName("2. currentProbe=null 往返（终止态会话，无在途探针）")
    void roundTrip_nullCurrentProbe() {
        Map<Long, NodeState> states = Map.of(
                1L, NodeState.TESTED_PASS,
                2L, NodeState.INFERRED_FAIL
        );
        AdaptiveDiagState original = new AdaptiveDiagState(
                states, 2, null,
                List.of(new AdaptiveDiagState.ProbeRecord(1L, List.of(11L), true))
        );

        AdaptiveDiagState restored = AdaptiveDiagState.fromJson(original.toJson());

        assertThat(restored.currentProbe()).isNull();
        assertThat(restored.budgetUsed()).isEqualTo(2);
        assertThat(restored.nodeStates()).isEqualTo(states);
        assertThat(restored.probeHistory()).hasSize(1);
    }

    @Test
    @DisplayName("3. 空 nodeStates + 空 probeHistory 边界往返")
    void roundTrip_emptyCollections() {
        AdaptiveDiagState original = new AdaptiveDiagState(Map.of(), 0, null, List.of());

        AdaptiveDiagState restored = AdaptiveDiagState.fromJson(original.toJson());

        assertThat(restored.nodeStates()).isEmpty();
        assertThat(restored.probeHistory()).isEmpty();
        assertThat(restored.budgetUsed()).isZero();
        assertThat(restored.currentProbe()).isNull();
    }

    @Test
    @DisplayName("4. 畸形 JSON 抛 IllegalStateException（state_json 服务端自产自销，畸形=程序 bug）")
    void malformedJson_throwsIllegalStateException() {
        // 明显非法的 JSON
        assertThatThrownBy(() -> AdaptiveDiagState.fromJson("{not valid json"))
                .isInstanceOf(IllegalStateException.class);

        // 结构类型不匹配（JSON 字符串无法反序列化为 record）
        assertThatThrownBy(() -> AdaptiveDiagState.fromJson("\"just a string\""))
                .isInstanceOf(IllegalStateException.class);
    }
}
