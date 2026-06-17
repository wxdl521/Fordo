package com.wenjin.ai;

import com.wenjin.dto.GraphImportRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAiCompatibleGraphCleanAiClient 单元测试：覆盖 prompt 构造与 JSON 解析（无网络调用）。
 */
class OpenAiCompatibleGraphCleanAiClientTest {

    // ============================ prompt 构造 ============================

    @Test
    @DisplayName("prompt 包含原始节点和边的 JSON 数据")
    void buildCleanPromptContainsData() {
        List<GraphImportRequest.NodeItem> nodes = List.of(makeNode("KT01", "用例图"));
        List<GraphImportRequest.EdgeItem> edges = List.of(makeEdge("KT01", "KT02", "依赖"));

        String prompt = OpenAiCompatibleGraphCleanAiClient.buildCleanPrompt(nodes, edges);

        assertThat(prompt).contains("KT01");
        assertThat(prompt).contains("用例图");
        assertThat(prompt).contains("依赖");
        assertThat(prompt).contains("前置");
        assertThat(prompt).contains("包含");
    }

    @Test
    @DisplayName("prompt 包含清洗规则说明")
    void buildCleanPromptContainsRules() {
        String prompt = OpenAiCompatibleGraphCleanAiClient.buildCleanPrompt(List.of(), List.of());

        assertThat(prompt).contains("关系类型标准化");
        assertThat(prompt).contains("difficulty 标准化");
        assertThat(prompt).contains("bloom 层级补充");
        assertThat(prompt).contains("is_key 标准化");
    }

    // ============================ JSON 解析 ============================

    @Test
    @DisplayName("解析标准 AI 清洗 JSON 结果")
    void parseCleanResultStandard() {
        String json = """
                {"nodes":[
                  {"id":"KT01","name":"用例图","chapter":"第1章","difficulty":3,"is_key":true,"bloom":"理解","description":"desc","note":""}
                ],"edges":[
                  {"source":"KT01","target":"KT02","type":"前置","note":""}
                ]}""";

        GraphImportRequest result = OpenAiCompatibleGraphCleanAiClient.parseCleanResult(json);

        assertThat(result.getNodes()).hasSize(1);
        assertThat(result.getNodes().get(0).getId()).isEqualTo("KT01");
        assertThat(result.getNodes().get(0).getName()).isEqualTo("用例图");
        assertThat(result.getNodes().get(0).getDifficulty()).isEqualTo(3);
        assertThat(result.getNodes().get(0).getIsKey()).isTrue();
        assertThat(result.getNodes().get(0).getBloom()).isEqualTo("理解");

        assertThat(result.getEdges()).hasSize(1);
        assertThat(result.getEdges().get(0).getSource()).isEqualTo("KT01");
        assertThat(result.getEdges().get(0).getTarget()).isEqualTo("KT02");
        assertThat(result.getEdges().get(0).getType()).isEqualTo("前置");
    }

    @Test
    @DisplayName("解析带 Markdown 围栏的 JSON")
    void parseCleanResultWithFence() {
        String fenced = "```json\n" +
                "{\"nodes\":[{\"id\":\"N1\",\"name\":\"A\",\"chapter\":\"\",\"difficulty\":3,\"is_key\":false,\"bloom\":\"\",\"description\":\"\",\"note\":\"\"}],\"edges\":[]}\n" +
                "```";

        GraphImportRequest result = OpenAiCompatibleGraphCleanAiClient.parseCleanResult(fenced);

        assertThat(result.getNodes()).hasSize(1);
        assertThat(result.getNodes().get(0).getId()).isEqualTo("N1");
    }

    @Test
    @DisplayName("解析前后带说明文字的 JSON")
    void parseCleanResultWithSurroundingText() {
        String content = "以下是清洗后的数据：\n" +
                "{\"nodes\":[{\"id\":\"N1\",\"name\":\"A\",\"chapter\":\"\",\"difficulty\":1,\"is_key\":false,\"bloom\":\"\",\"description\":\"\",\"note\":\"\"}],\"edges\":[]}\n" +
                "以上是清洗结果。";

        GraphImportRequest result = OpenAiCompatibleGraphCleanAiClient.parseCleanResult(content);

        assertThat(result.getNodes()).hasSize(1);
        assertThat(result.getNodes().get(0).getDifficulty()).isEqualTo(1);
    }

    @Test
    @DisplayName("解析空节点和边列表")
    void parseCleanResultEmpty() {
        String json = "{\"nodes\":[],\"edges\":[]}";

        GraphImportRequest result = OpenAiCompatibleGraphCleanAiClient.parseCleanResult(json);

        assertThat(result.getNodes()).isEmpty();
        assertThat(result.getEdges()).isEmpty();
    }

    @Test
    @DisplayName("解析缺少 is_key 字段时默认 false")
    void parseCleanResultMissingIsKey() {
        String json = """
                {"nodes":[
                  {"id":"KT01","name":"X","chapter":"","difficulty":3,"bloom":"","description":"","note":""}
                ],"edges":[]}""";

        GraphImportRequest result = OpenAiCompatibleGraphCleanAiClient.parseCleanResult(json);

        assertThat(result.getNodes().get(0).getIsKey()).isFalse();
    }

    // ============================ stripJsonFence ============================

    @Test
    @DisplayName("stripJsonFence 剥离 ```json 围栏")
    void stripJsonFenceRemovesFence() {
        String input = "```json\n{\"key\":\"value\"}\n```";
        String result = OpenAiCompatibleGraphCleanAiClient.stripJsonFence(input);
        assertThat(result).isEqualTo("{\"key\":\"value\"}");
    }

    @Test
    @DisplayName("stripJsonFence 处理无围栏的纯 JSON")
    void stripJsonFencePassesThrough() {
        String input = "{\"key\":\"value\"}";
        String result = OpenAiCompatibleGraphCleanAiClient.stripJsonFence(input);
        assertThat(result).isEqualTo("{\"key\":\"value\"}");
    }

    @Test
    @DisplayName("stripJsonFence 处理 null 输入")
    void stripJsonFenceNull() {
        assertThat(OpenAiCompatibleGraphCleanAiClient.stripJsonFence(null)).isEmpty();
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
