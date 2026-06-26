package com.wenjin.service;

import com.wenjin.dto.ExtractionMetrics;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.dto.GraphImportRequest.EdgeItem;
import com.wenjin.dto.GraphImportRequest.NodeItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 抽取审核指标计算:以老师审核后的最终结果为真值,对比 AI 原始草稿,
 * 得到节点/边各自的保留/删除/新增/修改数与召回率、精确率。
 *
 * 节点身份 = id;边身份 = source|target|type(类型变更体现为删+增,note 变更计入修改)。
 */
@Component
public class ExtractionMetricsCalculator {

    public ExtractionMetrics calculate(GraphImportRequest original, GraphImportRequest finalGraph) {
        ExtractionMetrics m = new ExtractionMetrics();
        m.setNode(nodeMetrics(safeNodes(original), safeNodes(finalGraph)));
        m.setEdge(edgeMetrics(safeEdges(original), safeEdges(finalGraph)));
        return m;
    }

    private ExtractionMetrics.MetricSet nodeMetrics(List<NodeItem> ai, List<NodeItem> fin) {
        Map<String, NodeItem> aiMap = new HashMap<>();
        for (NodeItem n : ai) {
            if (n.getId() != null) aiMap.put(n.getId(), n);
        }
        int kept = 0, modified = 0, added = 0, finalCount = 0;
        for (NodeItem f : fin) {
            if (f.getId() == null) continue;
            finalCount++;
            NodeItem a = aiMap.get(f.getId());
            if (a == null) {
                added++;
            } else {
                kept++;
                if (nodeChanged(a, f)) modified++;
            }
        }
        return build(aiMap.size(), kept, aiMap.size() - kept, added, modified, finalCount);
    }

    private boolean nodeChanged(NodeItem a, NodeItem b) {
        return !Objects.equals(a.getName(), b.getName())
                || !Objects.equals(a.getChapter(), b.getChapter())
                || !Objects.equals(a.getDifficulty(), b.getDifficulty())
                || !Objects.equals(a.getIsKey(), b.getIsKey())
                || !Objects.equals(a.getBloom(), b.getBloom())
                || !Objects.equals(a.getDescription(), b.getDescription());
    }

    private ExtractionMetrics.MetricSet edgeMetrics(List<EdgeItem> ai, List<EdgeItem> fin) {
        Map<String, EdgeItem> aiMap = new HashMap<>();
        for (EdgeItem e : ai) {
            aiMap.put(edgeKey(e), e);
        }
        int kept = 0, modified = 0, added = 0, finalCount = 0;
        for (EdgeItem f : fin) {
            finalCount++;
            EdgeItem a = aiMap.get(edgeKey(f));
            if (a == null) {
                added++;
            } else {
                kept++;
                if (!Objects.equals(a.getNote(), f.getNote())) modified++;
            }
        }
        return build(aiMap.size(), kept, aiMap.size() - kept, added, modified, finalCount);
    }

    private String edgeKey(EdgeItem e) {
        return e.getSource() + "|" + e.getTarget() + "|" + e.getType();
    }

    private ExtractionMetrics.MetricSet build(int ai, int kept, int deleted,
                                              int added, int modified, int finalCount) {
        ExtractionMetrics.MetricSet s = new ExtractionMetrics.MetricSet();
        s.setAiCount(ai);
        s.setKeptCount(kept);
        s.setDeletedCount(deleted);
        s.setAddedCount(added);
        s.setModifiedCount(modified);
        s.setFinalCount(finalCount);
        s.setPrecision(ratio(kept, ai));
        s.setRecall(ratio(kept, finalCount));
        return s;
    }

    private BigDecimal ratio(int numerator, int denominator) {
        if (denominator == 0) return null;
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private List<NodeItem> safeNodes(GraphImportRequest g) {
        return g == null || g.getNodes() == null ? List.of() : g.getNodes();
    }

    private List<EdgeItem> safeEdges(GraphImportRequest g) {
        return g == null || g.getEdges() == null ? List.of() : g.getEdges();
    }
}
