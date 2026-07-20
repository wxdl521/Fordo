package com.wenjin.eval.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeDimension;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * LLM-as-judge with JSON contract validation and limited retries.
 * <p>Invalid responses are retried up to {@link #MAX_RETRIES} times (≤2); then
 * {@link JudgeResult#success()} is false (judge failure, not a hard pass).
 */
public final class JudgeService {

    public static final String PROMPT_VERSION = "judge-prompt-v1";
    public static final String PROMPT_RESOURCE = "eval/judge-prompt-v1.md";
    /** Retries after the first attempt (total attempts = 1 + MAX_RETRIES). */
    public static final int MAX_RETRIES = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JudgeAiClient aiClient;
    private final String promptTemplate;
    private final String promptVersion;
    private final int maxRetries;

    public JudgeService(JudgeAiClient aiClient) {
        this(aiClient, loadDefaultTemplate(), PROMPT_VERSION, MAX_RETRIES);
    }

    public JudgeService(JudgeAiClient aiClient, String promptTemplate, String promptVersion, int maxRetries) {
        this.aiClient = Objects.requireNonNull(aiClient, "aiClient");
        this.promptTemplate = promptTemplate == null || promptTemplate.isBlank()
                ? loadDefaultTemplate()
                : promptTemplate;
        this.promptVersion = promptVersion == null || promptVersion.isBlank()
                ? PROMPT_VERSION
                : promptVersion;
        this.maxRetries = Math.max(0, maxRetries);
    }

    public String promptVersion() {
        return promptVersion;
    }

    public JudgeResult judge(JudgeRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.dimensions().isEmpty()) {
            return JudgeResult.failure(promptVersion, "no dimensions to score", 0);
        }

        String prompt = renderPrompt(request);
        int maxAttempts = 1 + maxRetries;
        String lastError = "unknown";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String raw;
            try {
                raw = aiClient.complete(prompt);
            } catch (Exception ex) {
                lastError = "AI call failed: " + ex.getMessage();
                continue;
            }
            try {
                List<JudgeScore> scores = parseAndValidate(raw, request.dimensions());
                return JudgeResult.ok(promptVersion, scores, attempt);
            } catch (IllegalArgumentException ex) {
                lastError = ex.getMessage();
            }
        }
        return JudgeResult.failure(promptVersion,
                "judge contract invalid after " + maxAttempts + " attempts: " + lastError,
                maxAttempts);
    }

    /**
     * Visible for tests: parse model output and validate against required dimensions.
     */
    public List<JudgeScore> parseAndValidate(String raw, List<JudgeDimension> dimensions) {
        Objects.requireNonNull(dimensions, "dimensions");
        if (dimensions.isEmpty()) {
            throw new IllegalArgumentException("dimensions required");
        }
        String json = extractJson(raw);
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getMessage());
        }
        JsonNode scoresNode = root.path("scores");
        if (!scoresNode.isArray()) {
            throw new IllegalArgumentException("missing scores array");
        }

        Map<String, JudgeScore> byKey = new LinkedHashMap<>();
        for (JsonNode n : scoresNode) {
            String key = n.path("key").asText(null);
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("score entry missing key");
            }
            if (!n.has("score") || !n.get("score").canConvertToInt()) {
                throw new IllegalArgumentException("score entry missing numeric score for key=" + key);
            }
            int score = n.get("score").asInt();
            if (score < 1 || score > 5) {
                throw new IllegalArgumentException("score out of range 1-5 for key=" + key + ": " + score);
            }
            String reason = n.path("reason").asText("");
            byKey.put(key, new JudgeScore(key, score, reason));
        }

        Set<String> required = new LinkedHashSet<>();
        for (JudgeDimension d : dimensions) {
            if (d != null && d.key() != null && !d.key().isBlank()) {
                required.add(d.key());
            }
        }
        if (required.isEmpty()) {
            throw new IllegalArgumentException("no valid dimension keys");
        }
        if (!byKey.keySet().containsAll(required)) {
            Set<String> missing = new LinkedHashSet<>(required);
            missing.removeAll(byKey.keySet());
            throw new IllegalArgumentException("missing dimensions: " + missing);
        }
        // Extra keys are ignored; order follows required dimensions.
        List<JudgeScore> ordered = new ArrayList<>();
        for (String key : required) {
            ordered.add(byKey.get(key));
        }
        return List.copyOf(ordered);
    }

    public String renderPrompt(JudgeRequest request) {
        String dims = formatDimensions(request.dimensions());
        return promptTemplate
                .replace("{{persona_note}}", nullToEmpty(request.personaNote()))
                .replace("{{transcript}}", nullToEmpty(request.transcript()))
                .replace("{{tool_summary}}", nullToEmpty(request.toolSummary()))
                .replace("{{dimensions}}", dims);
    }

    private static String formatDimensions(List<JudgeDimension> dimensions) {
        StringBuilder sb = new StringBuilder();
        for (JudgeDimension d : dimensions) {
            if (d == null || d.key() == null) {
                continue;
            }
            sb.append("- key=").append(d.key());
            if (d.desc() != null && !d.desc().isBlank()) {
                sb.append(" | ").append(d.desc());
            }
            if (d.threshold() != null) {
                sb.append(" | threshold≥").append(d.threshold());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty judge response");
        }
        String t = raw.trim();
        // Strip optional markdown fences
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) {
                t = t.substring(firstNl + 1);
            }
            int fence = t.lastIndexOf("```");
            if (fence >= 0) {
                t = t.substring(0, fence);
            }
            t = t.trim();
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("no JSON object in response");
        }
        return t.substring(start, end + 1);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String loadDefaultTemplate() {
        ClassPathResource resource = new ClassPathResource(PROMPT_RESOURCE);
        if (!resource.exists()) {
            return """
                    Score the companion turn.
                    Persona: {{persona_note}}
                    Transcript: {{transcript}}
                    Tools: {{tool_summary}}
                    Dimensions: {{dimensions}}
                    Return JSON: {"scores":[{"key":"...","score":1-5,"reason":"..."}]}
                    """;
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + PROMPT_RESOURCE, e);
        }
    }
}
