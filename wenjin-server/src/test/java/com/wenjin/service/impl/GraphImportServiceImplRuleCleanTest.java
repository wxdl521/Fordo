package com.wenjin.service.impl;

import com.wenjin.ai.GraphCleanAiClient;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.service.GraphService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GraphImportServiceImpl 规则清洗细节测试（同包，可访问 package-private 方法）。
 */
@ExtendWith(MockitoExtension.class)
class GraphImportServiceImplRuleCleanTest {

    @Mock GraphService graphService;
    @Mock GraphCleanAiClient graphCleanAiClient;

    private GraphImportServiceImpl service() {
        return new GraphImportServiceImpl(graphService, graphCleanAiClient);
    }

    @Test
    @DisplayName("依赖→前置，属于→包含")
    void mapsRelationSynonyms() {
        List<GraphImportRequest.EdgeItem> edges = List.of(
                makeEdge("KT01", "KT02", "依赖"),
                makeEdge("KT01", "KT02", "属于"));

        GraphImportRequest result = service().ruleBasedClean(List.of(makeNode("KT01", "A")), edges);

        assertThat(result.getEdges().get(0).getType()).isEqualTo("前置");
        assertThat(result.getEdges().get(1).getType()).isEqualTo("包含");
    }

    @Test
    @DisplayName("关联→相关，对应→应用")
    void mapsMoreSynonyms() {
        List<GraphImportRequest.EdgeItem> edges = List.of(
                makeEdge("KT01", "KT02", "关联"),
                makeEdge("KT01", "KT02", "对应"));

        GraphImportRequest result = service().ruleBasedClean(List.of(makeNode("KT01", "A")), edges);

        assertThat(result.getEdges().get(0).getType()).isEqualTo("相关");
        assertThat(result.getEdges().get(1).getType()).isEqualTo("应用");
    }

    @Test
    @DisplayName("已合法关系类型保留")
    void keepsValidRelationType() {
        List<GraphImportRequest.EdgeItem> edges = List.of(makeEdge("KT01", "KT02", "前置"));

        GraphImportRequest result = service().ruleBasedClean(List.of(makeNode("KT01", "A")), edges);

        assertThat(result.getEdges().get(0).getType()).isEqualTo("前置");
    }

    @Test
    @DisplayName("未知关系类型保留原值")
    void keepsUnknownRelationType() {
        List<GraphImportRequest.EdgeItem> edges = List.of(makeEdge("KT01", "KT02", "自定义类型"));

        GraphImportRequest result = service().ruleBasedClean(List.of(makeNode("KT01", "A")), edges);

        assertThat(result.getEdges().get(0).getType()).isEqualTo("自定义类型");
    }

    @Test
    @DisplayName("difficulty 越界或缺失修正为 3")
    void normalizesDifficulty() {
        GraphImportRequest.NodeItem n1 = makeNode("KT01", "A");
        n1.setDifficulty(0);
        GraphImportRequest.NodeItem n2 = makeNode("KT02", "B");
        n2.setDifficulty(6);
        GraphImportRequest.NodeItem n3 = makeNode("KT03", "C");
        n3.setDifficulty(null);

        GraphImportRequest result = service().ruleBasedClean(List.of(n1, n2, n3), List.of());

        assertThat(result.getNodes().get(0).getDifficulty()).isEqualTo(3);
        assertThat(result.getNodes().get(1).getDifficulty()).isEqualTo(3);
        assertThat(result.getNodes().get(2).getDifficulty()).isEqualTo(3);
    }

    @Test
    @DisplayName("difficulty 合法值保留")
    void keepsValidDifficulty() {
        GraphImportRequest.NodeItem n = makeNode("KT01", "A");
        n.setDifficulty(4);

        GraphImportRequest result = service().ruleBasedClean(List.of(n), List.of());

        assertThat(result.getNodes().get(0).getDifficulty()).isEqualTo(4);
    }

    @Test
    @DisplayName("空 id 自动生成 AUTO_ 序号")
    void autoGeneratesId() {
        GraphImportRequest.NodeItem n1 = new GraphImportRequest.NodeItem();
        n1.setId(null);
        n1.setName("A");
        GraphImportRequest.NodeItem n2 = new GraphImportRequest.NodeItem();
        n2.setId("  ");
        n2.setName("B");

        GraphImportRequest result = service().ruleBasedClean(List.of(n1, n2), List.of());

        assertThat(result.getNodes().get(0).getId()).isEqualTo("AUTO_1");
        assertThat(result.getNodes().get(1).getId()).isEqualTo("AUTO_2");
    }

    @Test
    @DisplayName("is_key 标准化")
    void normalizesIsKey() {
        GraphImportRequest.NodeItem n1 = makeNode("KT01", "A");
        n1.setIsKey(true);
        GraphImportRequest.NodeItem n2 = makeNode("KT02", "B");
        n2.setIsKey(false);
        GraphImportRequest.NodeItem n3 = makeNode("KT03", "C");
        n3.setIsKey(null);

        GraphImportRequest result = service().ruleBasedClean(List.of(n1, n2, n3), List.of());

        assertThat(result.getNodes().get(0).getIsKey()).isTrue();
        assertThat(result.getNodes().get(1).getIsKey()).isFalse();
        assertThat(result.getNodes().get(2).getIsKey()).isFalse();
    }

    @Test
    @DisplayName("bloom 字段保留原值")
    void keepsBloomValue() {
        GraphImportRequest.NodeItem n = makeNode("KT01", "A");
        n.setBloom("理解");

        GraphImportRequest result = service().ruleBasedClean(List.of(n), List.of());

        assertThat(result.getNodes().get(0).getBloom()).isEqualTo("理解");
    }

    // ============================ 辅助 ============================

    private GraphImportRequest.NodeItem makeNode(String id, String name) {
        GraphImportRequest.NodeItem n = new GraphImportRequest.NodeItem();
        n.setId(id);
        n.setName(name);
        n.setChapter("章");
        n.setDifficulty(3);
        n.setIsKey(false);
        return n;
    }

    private GraphImportRequest.EdgeItem makeEdge(String src, String tgt, String type) {
        GraphImportRequest.EdgeItem e = new GraphImportRequest.EdgeItem();
        e.setSource(src);
        e.setTarget(tgt);
        e.setType(type);
        return e;
    }
}
