package com.wenjin.eval.scenario;

import com.wenjin.eval.scenario.EvalScenarioDocument.HardAssertions;
import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeDimension;
import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeSpec;
import com.wenjin.eval.scenario.EvalScenarioDocument.MasteryOverride;
import com.wenjin.eval.scenario.EvalScenarioDocument.Persona;
import com.wenjin.eval.scenario.EvalScenarioDocument.ScriptTurn;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 校验评测场景文档结构（code / title / persona / script / hard / judge）。
 */
public final class ScenarioSchemaValidator {

    private static final Pattern CODE_PATTERN = Pattern.compile("^S\\d{2,3}$");
    private static final Set<String> ALLOWED_STATUS = Set.of("ENABLED", "DISABLED", "PENDING");

    private ScenarioSchemaValidator() {}

    /**
     * 校验场景文档；失败抛出 {@link ScenarioSchemaException}（消息含字段路径）。
     */
    public static void validate(EvalScenarioDocument doc) {
        if (doc == null) {
            throw new ScenarioSchemaException("scenario document is null");
        }
        requireNonBlank(doc.code(), "code");
        if (doc.code().length() > 32) {
            throw new ScenarioSchemaException("code length must be <= 32");
        }
        if (!CODE_PATTERN.matcher(doc.code()).matches()) {
            throw new ScenarioSchemaException("code must match S## (e.g. S01): " + doc.code());
        }

        requireNonBlank(doc.title(), "title");
        if (doc.title().length() > 100) {
            throw new ScenarioSchemaException("title length must be <= 100");
        }

        validateStatus(doc);
        validatePersona(doc.persona());
        validateScript(doc.script());
        validateHard(doc.hard());
        validateJudge(doc.judge());
        validateLiveFaults(doc.liveFaults());
    }

    private static void validateLiveFaults(EvalScenarioDocument.LiveFaults faults) {
        if (faults == null) {
            return;
        }
        if (faults.llmFailures() == null && faults.maxToolCalls() == null) {
            throw new ScenarioSchemaException("liveFaults must set llmFailures and/or maxToolCalls");
        }
        if (faults.llmFailures() != null && (faults.llmFailures() < 1 || faults.llmFailures() > 10)) {
            throw new ScenarioSchemaException("liveFaults.llmFailures must be in [1,10]");
        }
        if (faults.maxToolCalls() != null && (faults.maxToolCalls() < 0 || faults.maxToolCalls() > 20)) {
            throw new ScenarioSchemaException("liveFaults.maxToolCalls must be in [0,20]");
        }
    }

    private static void validateStatus(EvalScenarioDocument doc) {
        String status = doc.status();
        if (status != null && !status.isBlank()) {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if (!ALLOWED_STATUS.contains(normalized)) {
                throw new ScenarioSchemaException(
                        "status must be one of ENABLED|DISABLED|PENDING: " + status);
            }
            if ("PENDING".equals(normalized)) {
                if (doc.pendingReason() == null || doc.pendingReason().isBlank()) {
                    throw new ScenarioSchemaException(
                            "pendingReason is required when status=PENDING");
                }
                if (doc.pendingReason().length() > 64) {
                    throw new ScenarioSchemaException("pendingReason length must be <= 64");
                }
            }
        }
    }

    private static void validatePersona(Persona persona) {
        if (persona == null) {
            throw new ScenarioSchemaException("persona is required");
        }
        List<MasteryOverride> overrides = persona.masteryOverrides();
        if (overrides != null) {
            for (int i = 0; i < overrides.size(); i++) {
                MasteryOverride o = overrides.get(i);
                if (o == null) {
                    throw new ScenarioSchemaException("persona.masteryOverrides[" + i + "] is null");
                }
                if (o.nodeCode() == null || o.nodeCode().isBlank()) {
                    throw new ScenarioSchemaException(
                            "persona.masteryOverrides[" + i + "].nodeCode is required");
                }
                if (o.raw() != null && (o.raw() < 0.0 || o.raw() > 1.0)) {
                    throw new ScenarioSchemaException(
                            "persona.masteryOverrides[" + i + "].raw must be in [0,1]");
                }
                if (o.effective() != null && (o.effective() < 0.0 || o.effective() > 1.0)) {
                    throw new ScenarioSchemaException(
                            "persona.masteryOverrides[" + i + "].effective must be in [0,1]");
                }
            }
        }
        if (persona.reviewQueue() != null) {
            for (int i = 0; i < persona.reviewQueue().size(); i++) {
                String code = persona.reviewQueue().get(i);
                if (code == null || code.isBlank()) {
                    throw new ScenarioSchemaException(
                            "persona.reviewQueue[" + i + "] must be non-blank");
                }
            }
        }
    }

    private static void validateScript(List<ScriptTurn> script) {
        if (script == null || script.isEmpty()) {
            throw new ScenarioSchemaException("script must be a non-empty array");
        }
        for (int i = 0; i < script.size(); i++) {
            ScriptTurn turn = script.get(i);
            if (turn == null) {
                throw new ScenarioSchemaException("script[" + i + "] is null");
            }
            if (turn.user() == null || turn.user().isBlank()) {
                throw new ScenarioSchemaException("script[" + i + "].user is required");
            }
        }
    }

    private static void validateHard(HardAssertions hard) {
        if (hard == null) {
            throw new ScenarioSchemaException("hard is required");
        }
        if (hard.mustCallTools() != null) {
            for (int i = 0; i < hard.mustCallTools().size(); i++) {
                if (hard.mustCallTools().get(i) == null || hard.mustCallTools().get(i).isBlank()) {
                    throw new ScenarioSchemaException("hard.mustCallTools[" + i + "] must be non-blank");
                }
            }
        }
        if (hard.mustNotCallTools() != null) {
            for (int i = 0; i < hard.mustNotCallTools().size(); i++) {
                if (hard.mustNotCallTools().get(i) == null
                        || hard.mustNotCallTools().get(i).isBlank()) {
                    throw new ScenarioSchemaException(
                            "hard.mustNotCallTools[" + i + "] must be non-blank");
                }
            }
        }
        if (hard.maxToolCalls() != null && hard.maxToolCalls() < 0) {
            throw new ScenarioSchemaException("hard.maxToolCalls must be >= 0");
        }
        // Boolean fields may be null (optional); runner treats null as default later.
    }

    private static void validateJudge(JudgeSpec judge) {
        if (judge == null) {
            throw new ScenarioSchemaException("judge is required");
        }
        List<JudgeDimension> dimensions = judge.dimensions();
        if (dimensions == null || dimensions.isEmpty()) {
            throw new ScenarioSchemaException("judge.dimensions must be a non-empty array");
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < dimensions.size(); i++) {
            JudgeDimension d = dimensions.get(i);
            if (d == null) {
                throw new ScenarioSchemaException("judge.dimensions[" + i + "] is null");
            }
            if (d.key() == null || d.key().isBlank()) {
                throw new ScenarioSchemaException("judge.dimensions[" + i + "].key is required");
            }
            if (!keys.add(d.key())) {
                throw new ScenarioSchemaException(
                        "judge.dimensions[" + i + "].key is duplicated: " + d.key());
            }
            if (d.desc() == null || d.desc().isBlank()) {
                throw new ScenarioSchemaException("judge.dimensions[" + i + "].desc is required");
            }
            if (d.threshold() == null) {
                throw new ScenarioSchemaException(
                        "judge.dimensions[" + i + "].threshold is required");
            }
            if (d.threshold() < 1 || d.threshold() > 5) {
                throw new ScenarioSchemaException(
                        "judge.dimensions[" + i + "].threshold must be in [1,5]");
            }
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ScenarioSchemaException(field + " is required");
        }
    }
}
