package com.wenjin.support.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class CitationValidatorTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void tableDriven(String name,
                     List<Long> injected,
                     List<Long> cited,
                     List<Long> expectedKept,
                     List<Long> expectedStripped) {
        CitationValidator.Result r = CitationValidator.validate(injected, cited);
        assertThat(r.keptChunkIds()).as(name + " kept").containsExactlyElementsOf(expectedKept);
        assertThat(r.strippedChunkIds()).as(name + " stripped").containsExactlyElementsOf(expectedStripped);
        assertThat(r.hadStripped()).isEqualTo(!expectedStripped.isEmpty());
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                arguments("both_empty", List.of(), List.of(), List.of(), List.of()),
                arguments("nulls", null, null, List.of(), List.of()),
                arguments("all_valid", List.of(1L, 2L, 3L), List.of(2L, 1L), List.of(2L, 1L), List.of()),
                arguments("all_invalid", List.of(1L, 2L), List.of(9L, 8L), List.of(), List.of(9L, 8L)),
                arguments("mixed", List.of(10L, 11L), List.of(10L, 99L, 11L), List.of(10L, 11L), List.of(99L)),
                arguments("dedupe_cited", List.of(1L), List.of(1L, 1L, 2L), List.of(1L), List.of(2L)),
                arguments("empty_injected_strips_all", List.of(), List.of(5L), List.of(), List.of(5L)),
                arguments("null_ids_ignored", List.of(1L), java.util.Arrays.asList(1L, null, 7L), List.of(1L), List.of(7L))
        );
    }

    @Test
    @DisplayName("keptSet 为集合视图")
    void keptSet() {
        var r = CitationValidator.validate(List.of(1L, 2L), List.of(2L));
        assertThat(r.keptSet()).containsExactly(2L);
    }
}
