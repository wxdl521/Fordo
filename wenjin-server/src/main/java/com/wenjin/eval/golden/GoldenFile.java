package com.wenjin.eval.golden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

/**
 * Golden LLM replay document ({@code eval/golden/S0X.json}).
 *
 * <pre>
 * {"scenario":"S0X","recordedAt":"...","model":"deepseek-chat",
 *  "llmCalls":[{"idx":0,"name":"phase1","output":"..."},
 *              {"idx":1,"name":"phase2","output":"..."}]}
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoldenFile(
        String scenario,
        String recordedAt,
        String model,
        List<GoldenLlmCall> llmCalls
) {
    public GoldenFile {
        Objects.requireNonNull(scenario, "scenario");
        recordedAt = recordedAt == null ? "" : recordedAt;
        model = model == null ? "" : model;
        llmCalls = llmCalls == null ? List.of() : List.copyOf(llmCalls);
    }
}
