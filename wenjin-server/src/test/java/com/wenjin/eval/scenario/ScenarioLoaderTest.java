package com.wenjin.eval.scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioLoaderTest {

    private final ScenarioLoader loader = new ScenarioLoader();

    @Test
    @DisplayName("classpath 场景库可全部加载且 schema 合法（含 S01/S09）")
    void loadAll_validScenariosPassSchema() {
        List<EvalScenarioDocument> docs = loader.loadAll();

        assertThat(docs).isNotEmpty();
        Set<String> codes = docs.stream().map(EvalScenarioDocument::code).collect(Collectors.toSet());
        assertThat(codes).contains("S01", "S09");
        assertThat(codes).hasSize(docs.size());

        Optional<EvalScenarioDocument> s01 = loader.loadByCode("S01");
        assertThat(s01).isPresent();
        assertThat(s01.get().title()).contains("弱节点");
        assertThat(s01.get().hard().mustCallTools()).contains("query_mastery");
        assertThat(s01.get().judge().dimensions()).isNotEmpty();
        assertThat(s01.get().resolvedStatus()).isEqualTo(EvalScenarioStatus.ENABLED);

        Optional<EvalScenarioDocument> s09 = loader.loadByCode("S09");
        assertThat(s09).isPresent();
        assertThat(s09.get().script()).hasSize(1);
        assertThat(s09.get().resolvedStatus()).isEqualTo(EvalScenarioStatus.ENABLED);

        Optional<EvalScenarioDocument> s07 = loader.loadByCode("S07");
        assertThat(s07).isPresent();
        assertThat(s07.get().resolvedStatus()).isEqualTo(EvalScenarioStatus.ENABLED);
        assertThat(s07.get().hard().degradedExpected()).isTrue();

        Optional<EvalScenarioDocument> s17 = loader.loadByCode("S17");
        assertThat(s17).isPresent();
        assertThat(s17.get().resolvedStatus()).isEqualTo(EvalScenarioStatus.ENABLED);
        assertThat(s17.get().hard().mustNotCallTools()).contains("probe_understanding");
    }

    @Test
    @DisplayName("非法 JSON：缺少 code → schema 失败")
    void parse_missingCode_fails() throws Exception {
        assertInvalid("eval/invalid/missing-code.json", "code");
    }

    @Test
    @DisplayName("非法 JSON：空 script → schema 失败")
    void parse_emptyScript_fails() throws Exception {
        assertInvalid("eval/invalid/empty-script.json", "script");
    }

    @Test
    @DisplayName("非法 JSON：threshold 越界 → schema 失败")
    void parse_badThreshold_fails() throws Exception {
        assertInvalid("eval/invalid/bad-threshold.json", "threshold");
    }

    @Test
    @DisplayName("非法 JSON：PENDING 无 pendingReason → schema 失败")
    void parse_pendingWithoutReason_fails() throws Exception {
        assertInvalid("eval/invalid/pending-without-reason.json", "pendingReason");
    }

    @Test
    @DisplayName("非法 JSON：未知字段 → 反序列化失败")
    void parse_unknownField_fails() throws Exception {
        ClassPathResource resource = new ClassPathResource("eval/invalid/unknown-field.json");
        try (InputStream in = resource.getInputStream()) {
            assertThatThrownBy(() -> loader.parseAndValidate(in, resource.getFilename()))
                    .isInstanceOf(ScenarioSchemaException.class)
                    .hasMessageContaining("Invalid scenario JSON");
        }
    }

    private void assertInvalid(String classpath, String messagePart) throws Exception {
        ClassPathResource resource = new ClassPathResource(classpath);
        try (InputStream in = resource.getInputStream()) {
            assertThatThrownBy(() -> loader.parseAndValidate(in, resource.getFilename()))
                    .isInstanceOf(ScenarioSchemaException.class)
                    .hasMessageContaining(messagePart);
        }
    }
}
