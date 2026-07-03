package com.wenjin.support;

import com.wenjin.entity.KgEdge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PrereqDirection 方向断言测试（M2 T1 TDD）。
 *
 * <p>3 节点链式小图：A 是 B 的前置，B 是 C 的前置。
 * 存储方式：edge(from=A, to=B) + edge(from=B, to=C)，即 from_node_id 为先修节点。
 *
 * <p>测试目标：
 * <ol>
 *   <li>prerequisiteNodeId / dependentNodeId 对每条边返回正确端点；</li>
 *   <li>用访问器做 BFS：C 的"前置祖先集"= {A, B}，A 的"后继子孙集"= {B, C}。</li>
 * </ol>
 */
class PrereqDirectionTest {

    private static final long A = 1L;  // 最上游：无前置
    private static final long B = 2L;  // 中间节点
    private static final long C = 3L;  // 最下游：目标节点

    /** edge(from=A, to=B)：A 是 B 的前置 */
    private final KgEdge edgeAB = edge(A, B);
    /** edge(from=B, to=C)：B 是 C 的前置 */
    private final KgEdge edgeBC = edge(B, C);

    private static KgEdge edge(long from, long to) {
        KgEdge e = new KgEdge();
        e.setFromNodeId(from);
        e.setToNodeId(to);
        e.setRelationType(PrereqDirection.RELATION_TYPE_PREREQUISITE);
        return e;
    }

    // ── 访问器断言 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("prerequisiteNodeId 返回 from_node_id（先修节点）")
    void prerequisiteNodeId_returnsFromNode() {
        assertThat(PrereqDirection.prerequisiteNodeId(edgeAB)).isEqualTo(A);
        assertThat(PrereqDirection.prerequisiteNodeId(edgeBC)).isEqualTo(B);
    }

    @Test
    @DisplayName("dependentNodeId 返回 to_node_id（后继节点）")
    void dependentNodeId_returnsTooNode() {
        assertThat(PrereqDirection.dependentNodeId(edgeAB)).isEqualTo(B);
        assertThat(PrereqDirection.dependentNodeId(edgeBC)).isEqualTo(C);
    }

    @Test
    @DisplayName("RELATION_TYPE_PREREQUISITE 常量值为 1")
    void constantValue_isOne() {
        assertThat(PrereqDirection.RELATION_TYPE_PREREQUISITE).isEqualTo(1);
    }

    // ── BFS 方向验证 ────────────────────────────────────────────────────────

    /**
     * C 的前置祖先集（BFS 沿"to->前置"方向回溯）= {A, B}。
     *
     * <p>构造方式：prereqOf[to] = List<from>，即 toNodeId -> [prerequisiteNodeId]。
     */
    @Test
    @DisplayName("BFS 逆向：C 的前置祖先集 = {A, B}")
    void bfs_ancestorsOfC_areAandB() {
        List<KgEdge> edges = List.of(edgeAB, edgeBC);
        Map<Long, List<Long>> prereqOf = buildPrereqOf(edges);

        Set<Long> ancestors = bfsAncestors(C, prereqOf);

        assertThat(ancestors).containsExactlyInAnyOrder(A, B);
    }

    /**
     * A 的后继子孙集（BFS 沿"from->后继"方向正向遍历）= {B, C}。
     *
     * <p>构造方式：successorsOf[from] = List<to>，即 fromNodeId -> [dependentNodeId]。
     */
    @Test
    @DisplayName("BFS 正向：A 的后继子孙集 = {B, C}")
    void bfs_descendantsOfA_areBandC() {
        List<KgEdge> edges = List.of(edgeAB, edgeBC);
        Map<Long, List<Long>> successorsOf = buildSuccessorsOf(edges);

        Set<Long> descendants = bfsDescendants(A, successorsOf);

        assertThat(descendants).containsExactlyInAnyOrder(B, C);
    }

    // ── BFS 工具（测试内联，不依赖主代码——那是 T2 的活） ──────────────────

    /**
     * 用 PrereqDirection 访问器构造 toNodeId -> [prerequisiteNodeId] 映射。
     */
    private static Map<Long, List<Long>> buildPrereqOf(List<KgEdge> edges) {
        Map<Long, List<Long>> map = new HashMap<>();
        for (KgEdge e : edges) {
            map.computeIfAbsent(PrereqDirection.dependentNodeId(e), k -> new ArrayList<>())
               .add(PrereqDirection.prerequisiteNodeId(e));
        }
        return map;
    }

    /**
     * 用 PrereqDirection 访问器构造 fromNodeId -> [dependentNodeId] 映射。
     */
    private static Map<Long, List<Long>> buildSuccessorsOf(List<KgEdge> edges) {
        Map<Long, List<Long>> map = new HashMap<>();
        for (KgEdge e : edges) {
            map.computeIfAbsent(PrereqDirection.prerequisiteNodeId(e), k -> new ArrayList<>())
               .add(PrereqDirection.dependentNodeId(e));
        }
        return map;
    }

    /** 逆向 BFS：给定目标节点，返回所有前置祖先（不含自身）。 */
    private static Set<Long> bfsAncestors(long startId, Map<Long, List<Long>> prereqOf) {
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(startId);
        while (!queue.isEmpty()) {
            Long cur = queue.poll();
            for (Long prereq : prereqOf.getOrDefault(cur, List.of())) {
                if (visited.add(prereq)) {
                    queue.add(prereq);
                }
            }
        }
        return visited;
    }

    /** 正向 BFS：给定根节点，返回所有后继子孙（不含自身）。 */
    private static Set<Long> bfsDescendants(long startId, Map<Long, List<Long>> successorsOf) {
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(startId);
        while (!queue.isEmpty()) {
            Long cur = queue.poll();
            for (Long succ : successorsOf.getOrDefault(cur, List.of())) {
                if (visited.add(succ)) {
                    queue.add(succ);
                }
            }
        }
        return visited;
    }
}
