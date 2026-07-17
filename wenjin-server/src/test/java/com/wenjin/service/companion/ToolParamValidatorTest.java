package com.wenjin.service.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Table-driven coverage for {@link ToolParamValidator} (≥10 groups).
 */
class ToolParamValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("ToolParamSpec table-driven validation")
    void validates(String name, List<ToolParamSpec> specs, String argsJson, String expectedErrorContains)
            throws Exception {
        ObjectNode args = (ObjectNode) MAPPER.readTree(argsJson == null ? "{}" : argsJson);
        String error = ToolParamValidator.validate(specs, args);
        if (expectedErrorContains == null) {
            assertThat(error).as(name).isNull();
        } else {
            assertThat(error).as(name).contains(expectedErrorContains);
        }
    }

    static Stream<Arguments> cases() {
        ToolParamSpec nodeCode = ToolParamSpec.string(
                "nodeCode", true, 32, ToolParamSpec.NODE_CODE_PATTERN);
        ToolParamSpec nodeName = ToolParamSpec.string("nodeName", false, 8, null);
        ToolParamSpec size = ToolParamSpec.integer("size", false, 1.0, 3.0);
        ToolParamSpec flag = ToolParamSpec.bool("enabled", true);
        ToolParamSpec tags = ToolParamSpec.array("tags", false);
        ToolParamSpec mode = new ToolParamSpec(
                "mode", ToolParamSpec.ParamType.STRING, true, null, null, null,
                List.of("a", "b"), null);

        return Stream.of(
                Arguments.of("required string present",
                        List.of(nodeCode), "{\"nodeCode\":\"KT12\"}", null),
                Arguments.of("required string missing",
                        List.of(nodeCode), "{}", "Missing required tool argument: nodeCode"),
                Arguments.of("string type mismatch",
                        List.of(nodeCode), "{\"nodeCode\":12}", "invalid type: nodeCode"),
                Arguments.of("nodeCode pattern reject",
                        List.of(nodeCode), "{\"nodeCode\":\"!!bad!!\"}", "invalid format: nodeCode"),
                Arguments.of("nodeCode pattern accept",
                        List.of(ToolParamSpec.string("nodeCode", false, 32, ToolParamSpec.NODE_CODE_PATTERN)),
                        "{\"nodeCode\":\"KT_01\"}", null),
                Arguments.of("maxLen exceeded",
                        List.of(nodeName), "{\"nodeName\":\"abcdefghij\"}", "exceeds max length: nodeName"),
                Arguments.of("maxLen ok",
                        List.of(nodeName), "{\"nodeName\":\"abc\"}", null),
                Arguments.of("int below min",
                        List.of(size), "{\"size\":0}", "below minimum: size"),
                Arguments.of("int above max",
                        List.of(size), "{\"size\":9}", "above maximum: size"),
                Arguments.of("int type mismatch",
                        List.of(size), "{\"size\":\"2\"}", "invalid type: size"),
                Arguments.of("int in range",
                        List.of(size), "{\"size\":2}", null),
                Arguments.of("bool required missing",
                        List.of(flag), "{}", "Missing required tool argument: enabled"),
                Arguments.of("bool type mismatch",
                        List.of(flag), "{\"enabled\":\"yes\"}", "invalid type: enabled"),
                Arguments.of("array type mismatch",
                        List.of(tags), "{\"tags\":\"x\"}", "invalid type: tags"),
                Arguments.of("array ok",
                        List.of(tags), "{\"tags\":[\"a\"]}", null),
                Arguments.of("enum reject",
                        List.of(mode), "{\"mode\":\"c\"}", "invalid value: mode"),
                Arguments.of("enum accept",
                        List.of(mode), "{\"mode\":\"a\"}", null),
                Arguments.of("empty specs always pass",
                        List.of(), "{\"anything\":true}", null),
                Arguments.of("optional absent is ok",
                        List.of(size), "{}", null)
        );
    }
}
