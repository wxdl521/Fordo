package com.wenjin.eval.golden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One recorded LLM_CALL entry in a golden file.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoldenLlmCall(
        int idx,
        String name,
        String output
) {
    public GoldenLlmCall {
        name = name == null ? "" : name;
        output = output == null ? "" : output;
    }
}
