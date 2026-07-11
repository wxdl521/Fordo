package com.wenjin.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「前置」关系环路检测（DFS 三色标记）。
 * <p>供图谱导入校验与教师端单边创建复用。
 */
public final class PrerequisiteCycleDetector {

    private PrerequisiteCycleDetector() {
    }

    /**
     * 在有向邻接表上检测环。
     *
     * @param adj 邻接表：source → targets（仅前置边）
     * @return 第一条环路径（节点编码序列，首尾相接）；无环返回 null
     */
    public static List<String> detect(Map<String, List<String>> adj) {
        if (adj == null || adj.isEmpty()) {
            return null;
        }
        Map<String, Integer> state = new HashMap<>();
        List<String> stack = new ArrayList<>();
        for (String start : adj.keySet()) {
            if (state.getOrDefault(start, 0) == 0) {
                List<String> cycle = dfs(start, adj, state, stack);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        return null;
    }

    /**
     * 向既有邻接表追加一条边后检测是否成环（不修改入参）。
     *
     * @param existingAdj 既有前置边邻接表（可为 null）
     * @param from        新边起点
     * @param to          新边终点
     * @return 环路径；无环返回 null
     */
    public static List<String> detectIfAdd(Map<String, List<String>> existingAdj, String from, String to) {
        Map<String, List<String>> adj = copyAdj(existingAdj);
        if (from != null && to != null) {
            adj.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        }
        return detect(adj);
    }

    private static Map<String, List<String>> copyAdj(Map<String, List<String>> src) {
        Map<String, List<String>> copy = new HashMap<>();
        if (src == null) {
            return copy;
        }
        for (Map.Entry<String, List<String>> e : src.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue() == null ? List.of() : e.getValue()));
        }
        return copy;
    }

    private static List<String> dfs(String node, Map<String, List<String>> adj,
                                    Map<String, Integer> state, List<String> stack) {
        state.put(node, 1);
        stack.add(node);
        for (String next : adj.getOrDefault(node, List.of())) {
            Integer st = state.getOrDefault(next, 0);
            if (st == 1) {
                int idx = stack.indexOf(next);
                List<String> cycle = new ArrayList<>(stack.subList(idx, stack.size()));
                cycle.add(next);
                return cycle;
            }
            if (st == 0) {
                List<String> cycle = dfs(next, adj, state, stack);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        stack.remove(stack.size() - 1);
        state.put(node, 2);
        return null;
    }
}
