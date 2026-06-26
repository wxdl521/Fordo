package com.wenjin.service;

import com.wenjin.dto.ExtractionMetrics;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.dto.GraphImportRequest.EdgeItem;
import com.wenjin.dto.GraphImportRequest.NodeItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExtractionMetricsCalculatorTest {

    private final ExtractionMetricsCalculator calc = new ExtractionMetricsCalculator();

    private NodeItem node(String id, String name) {
        NodeItem n = new NodeItem();
        n.setId(id);
        n.setName(name);
        return n;
    }

    private EdgeItem edge(String s, String t, String type) {
        EdgeItem e = new EdgeItem();
        e.setSource(s);
        e.setTarget(t);
        e.setType(type);
        return e;
    }

    private GraphImportRequest graph(List<NodeItem> nodes, List<EdgeItem> edges) {
        GraphImportRequest g = new GraphImportRequest();
        g.setNodes(nodes);
        g.setEdges(edges);
        return g;
    }

    @Test
    void nodes_keptDeletedAddedModified() {
        GraphImportRequest ai = graph(
                new ArrayList<>(List.of(node("A", "甲"), node("B", "乙"), node("C", "丙"))),
                new ArrayList<>());
        // A 保留原样, B 改名(修改), C 删除, D 新增
        GraphImportRequest fin = graph(
                new ArrayList<>(List.of(node("A", "甲"), node("B", "乙改"), node("D", "丁"))),
                new ArrayList<>());

        ExtractionMetrics.MetricSet n = calc.calculate(ai, fin).getNode();
        assertEquals(3, n.getAiCount());
        assertEquals(2, n.getKeptCount());     // A,B
        assertEquals(1, n.getDeletedCount());  // C
        assertEquals(1, n.getAddedCount());    // D
        assertEquals(1, n.getModifiedCount()); // B
        assertEquals(3, n.getFinalCount());    // A,B,D
        assertEquals(new BigDecimal("0.6667"), n.getRecall());    // 2/3
        assertEquals(new BigDecimal("0.6667"), n.getPrecision()); // 2/3
    }

    @Test
    void edges_addedDeleted_byKey() {
        GraphImportRequest ai = graph(new ArrayList<>(),
                new ArrayList<>(List.of(edge("A", "B", "前置"), edge("B", "C", "包含"))));
        // 保留 A-B 前置, 删除 B-C 包含, 新增 A-C 相关
        GraphImportRequest fin = graph(new ArrayList<>(),
                new ArrayList<>(List.of(edge("A", "B", "前置"), edge("A", "C", "相关"))));

        ExtractionMetrics.MetricSet e = calc.calculate(ai, fin).getEdge();
        assertEquals(2, e.getAiCount());
        assertEquals(1, e.getKeptCount());
        assertEquals(1, e.getDeletedCount());
        assertEquals(1, e.getAddedCount());
        assertEquals(2, e.getFinalCount());
    }

    @Test
    void emptyDenominators_giveNullRatios() {
        GraphImportRequest empty = graph(new ArrayList<>(), new ArrayList<>());
        ExtractionMetrics.MetricSet n = calc.calculate(empty, empty).getNode();
        assertNull(n.getRecall());
        assertNull(n.getPrecision());
        assertEquals(0, n.getFinalCount());
    }

    @Test
    void edges_noteChange_countsModified_andEmptyEdgeRatiosNull() {
        EdgeItem a = edge("A", "B", "前置");
        a.setNote("原备注");
        EdgeItem a2 = edge("A", "B", "前置");
        a2.setNote("改后备注");
        GraphImportRequest ai = graph(new ArrayList<>(), new ArrayList<>(List.of(a)));
        GraphImportRequest fin = graph(new ArrayList<>(), new ArrayList<>(List.of(a2)));

        ExtractionMetrics.MetricSet e = calc.calculate(ai, fin).getEdge();
        assertEquals(1, e.getKeptCount());
        assertEquals(1, e.getModifiedCount());

        GraphImportRequest empty = graph(new ArrayList<>(), new ArrayList<>());
        assertNull(calc.calculate(empty, empty).getEdge().getRecall());
        assertNull(calc.calculate(empty, empty).getEdge().getPrecision());
    }
}
