package com.wenjin.eval.scenario;

import com.wenjin.eval.scenario.EvalScenarioDocument.HardAssertions;
import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeDimension;
import com.wenjin.eval.scenario.EvalScenarioDocument.JudgeSpec;
import com.wenjin.eval.scenario.EvalScenarioDocument.MasteryOverride;
import com.wenjin.eval.scenario.EvalScenarioDocument.Persona;
import com.wenjin.eval.scenario.EvalScenarioDocument.RosterStudent;
import com.wenjin.eval.scenario.EvalScenarioDocument.ScriptTurn;
import com.wenjin.eval.scenario.EvalScenarioDocument.SnapshotSeed;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 校验评测场景文档结构（code / title / persona / script / hard / judge）。
 */
public final class ScenarioSchemaValidator {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[ST]\\d{2,3}$");
    private static final Set<String> ALLOWED_STATUS = Set.of("ENABLED", "DISABLED", "PENDING");
    static final int MAX_MISTAKES = 10;
    static final int MAX_ROSTER = 5;
    static final int MAX_ROSTER_ANSWERS = 50;
    static final int MAX_DAYS_AGO = 365;
    private static final Set<String> ALLOWED_ACTORS = Set.of("student", "teacher");

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
            throw new ScenarioSchemaException("code must match S## or T## (e.g. S01, T01): " + doc.code());
        }

        requireNonBlank(doc.title(), "title");
        if (doc.title().length() > 100) {
            throw new ScenarioSchemaException("title length must be <= 100");
        }

        validateStatus(doc);
        validateActor(doc);
        validatePersona(doc.persona());
        validateRoster(doc);
        validateScript(doc.script());
        validateHard(doc.hard());
        validateJudge(doc.judge());
        validateLiveFaults(doc.liveFaults());
    }

    private static void validateActor(EvalScenarioDocument doc) {
        if (doc.actor() != null && !ALLOWED_ACTORS.contains(doc.actor().trim().toLowerCase(Locale.ROOT))) {
            throw new ScenarioSchemaException("actor must be one of student|teacher: " + doc.actor());
        }
    }

    private static void validateRoster(EvalScenarioDocument doc) {
        List<RosterStudent> roster = doc.persona() == null ? null : doc.persona().roster();
        if (roster == null || roster.isEmpty()) {
            return;
        }
        if (!doc.forTeacher()) {
            throw new ScenarioSchemaException("persona.roster is only allowed when actor=teacher");
        }
        if (roster.size() > MAX_ROSTER) {
            throw new ScenarioSchemaException("persona.roster must have at most " + MAX_ROSTER + " entries");
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < roster.size(); i++) {
            RosterStudent s = roster.get(i);
            String at = "persona.roster[" + i + "]";
            if (s == null) {
                throw new ScenarioSchemaException(at + " is null");
            }
            if (s.name() == null || s.name().isBlank()) {
                throw new ScenarioSchemaException(at + ".name is required");
            }
            if (s.name().trim().length() > 20) {
                throw new ScenarioSchemaException(at + ".name length must be <= 20");
            }
            if (!names.add(s.name().trim())) {
                throw new ScenarioSchemaException(at + ".name is duplicated: " + s.name());
            }
            validateRosterMastery(s, at);
            validateRosterMistakes(s, at);
            if (s.correctAnswers() != null && (s.correctAnswers() < 0 || s.correctAnswers() > MAX_ROSTER_ANSWERS)) {
                throw new ScenarioSchemaException(at + ".correctAnswers must be in [0," + MAX_ROSTER_ANSWERS + "]");
            }
            if (s.lastPracticeDaysAgo() != null
                    && (s.lastPracticeDaysAgo() < 0 || s.lastPracticeDaysAgo() > MAX_DAYS_AGO)) {
                throw new ScenarioSchemaException(at + ".lastPracticeDaysAgo must be in [0," + MAX_DAYS_AGO + "]");
            }
            validateRosterSnapshots(s, at);
            if (s.privateChat() != null && s.privateChat().length() > 500) {
                throw new ScenarioSchemaException(at + ".privateChat length must be <= 500");
            }
        }
    }

    private static void validateRosterMastery(RosterStudent s, String at) {
        if (s.masteryOverrides() == null) {
            return;
        }
        for (int j = 0; j < s.masteryOverrides().size(); j++) {
            MasteryOverride o = s.masteryOverrides().get(j);
            String m = at + ".masteryOverrides[" + j + "]";
            if (o == null || o.nodeCode() == null || o.nodeCode().isBlank()) {
                throw new ScenarioSchemaException(m + ".nodeCode is required");
            }
            if (o.raw() == null || o.raw() < 0.0 || o.raw() > 1.0) {
                throw new ScenarioSchemaException(m + ".raw is required and must be in [0,1]");
            }
            if (o.effective() != null) {
                throw new ScenarioSchemaException(m + ".effective is not supported for roster students");
            }
        }
    }

    private static void validateRosterMistakes(RosterStudent s, String at) {
        if (s.mistakes() == null) {
            return;
        }
        if (s.mistakes().size() > MAX_MISTAKES) {
            throw new ScenarioSchemaException(at + ".mistakes must have at most " + MAX_MISTAKES + " entries");
        }
        for (int j = 0; j < s.mistakes().size(); j++) {
            EvalScenarioDocument.MistakeSeed m = s.mistakes().get(j);
            if (m == null || m.nodeCode() == null || m.nodeCode().isBlank()
                    || m.misconceptionNodeCode() == null || m.misconceptionNodeCode().isBlank()) {
                throw new ScenarioSchemaException(at + ".mistakes[" + j
                        + "] needs nodeCode and misconceptionNodeCode");
            }
        }
    }

    private static void validateRosterSnapshots(RosterStudent s, String at) {
        if (s.snapshots() == null) {
            return;
        }
        for (int j = 0; j < s.snapshots().size(); j++) {
            SnapshotSeed snap = s.snapshots().get(j);
            String sn = at + ".snapshots[" + j + "]";
            if (snap == null || snap.nodeCode() == null || snap.nodeCode().isBlank()) {
                throw new ScenarioSchemaException(sn + ".nodeCode is required");
            }
            if (snap.daysAgo() == null || snap.daysAgo() < 1 || snap.daysAgo() > MAX_DAYS_AGO) {
                throw new ScenarioSchemaException(sn + ".daysAgo must be in [1," + MAX_DAYS_AGO + "]");
            }
            if (snap.score() == null || snap.score() < 0.0 || snap.score() > 1.0) {
                throw new ScenarioSchemaException(sn + ".score must be in [0,1]");
            }
        }
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
        if (persona.mistakes() != null) {
            if (persona.mistakes().size() > MAX_MISTAKES) {
                throw new ScenarioSchemaException("persona.mistakes must have at most " + MAX_MISTAKES + " entries");
            }
            for (int i = 0; i < persona.mistakes().size(); i++) {
                EvalScenarioDocument.MistakeSeed m = persona.mistakes().get(i);
                if (m == null) {
                    throw new ScenarioSchemaException("persona.mistakes[" + i + "] is null");
                }
                if (m.nodeCode() == null || m.nodeCode().isBlank()) {
                    throw new ScenarioSchemaException("persona.mistakes[" + i + "].nodeCode is required");
                }
                if (m.misconceptionNodeCode() == null || m.misconceptionNodeCode().isBlank()) {
                    throw new ScenarioSchemaException(
                            "persona.mistakes[" + i + "].misconceptionNodeCode is required");
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
        if (hard.mustCallAnyOf() != null) {
            if (hard.mustCallAnyOf().isEmpty()) {
                throw new ScenarioSchemaException("hard.mustCallAnyOf must be non-empty when present");
            }
            for (int i = 0; i < hard.mustCallAnyOf().size(); i++) {
                List<String> group = hard.mustCallAnyOf().get(i);
                if (group == null || group.isEmpty()) {
                    throw new ScenarioSchemaException("hard.mustCallAnyOf[" + i + "] must be a non-empty array");
                }
                for (int j = 0; j < group.size(); j++) {
                    if (group.get(j) == null || group.get(j).isBlank()) {
                        throw new ScenarioSchemaException("hard.mustCallAnyOf[" + i + "][" + j + "] must be non-blank");
                    }
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
        if (hard.replyMustNotContain() != null) {
            for (int i = 0; i < hard.replyMustNotContain().size(); i++) {
                String s = hard.replyMustNotContain().get(i);
                if (s == null || s.isBlank()) {
                    throw new ScenarioSchemaException("hard.replyMustNotContain[" + i + "] must be non-blank");
                }
            }
        }
        if (hard.replyMustContain() != null) {
            for (int i = 0; i < hard.replyMustContain().size(); i++) {
                String s = hard.replyMustContain().get(i);
                if (s == null || s.isBlank()) {
                    throw new ScenarioSchemaException("hard.replyMustContain[" + i + "] must be non-blank");
                }
            }
        }
        if (hard.guardCodesExpected() != null) {
            for (int i = 0; i < hard.guardCodesExpected().size(); i++) {
                String s = hard.guardCodesExpected().get(i);
                if (s == null || s.isBlank()) {
                    throw new ScenarioSchemaException("hard.guardCodesExpected[" + i + "] must be non-blank");
                }
            }
        }
        if (hard.maxToolCalls() != null && hard.maxToolCalls() < 0) {
            throw new ScenarioSchemaException("hard.maxToolCalls must be >= 0");
        }
        validateReplayOnly(hard);
        // Boolean fields may be null (optional); runner treats null as default later.
    }

    /** replayOnly：每项须是已知断言键、不重复，且该断言在本场景确实设置了（否则标注无意义，多半是笔误）。 */
    private static void validateReplayOnly(HardAssertions hard) {
        List<String> keys = hard.replayOnly();
        if (keys == null) {
            return;
        }
        if (keys.isEmpty()) {
            throw new ScenarioSchemaException("hard.replayOnly must be non-empty when present");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            String at = "hard.replayOnly[" + i + "]";
            if (key == null || !HardAssertions.KEYS.contains(key)) {
                throw new ScenarioSchemaException(at + " must be one of " + new java.util.TreeSet<>(HardAssertions.KEYS)
                        + ": " + key);
            }
            if (!seen.add(key)) {
                throw new ScenarioSchemaException(at + " is duplicated: " + key);
            }
            if (!isSet(hard, key)) {
                throw new ScenarioSchemaException(at + " refers to hard." + key + " which is not set");
            }
        }
    }

    private static boolean isSet(HardAssertions hard, String key) {
        Object value = switch (key) {
            case "mustCallTools" -> hard.mustCallTools();
            case "mustNotCallTools" -> hard.mustNotCallTools();
            case "maxToolCalls" -> hard.maxToolCalls();
            case "jsonContractValid" -> hard.jsonContractValid();
            case "nodeCodesInWhitelist" -> hard.nodeCodesInWhitelist();
            case "degradedExpected" -> hard.degradedExpected();
            case "citationsValid" -> hard.citationsValid();
            case "replyMustNotContain" -> hard.replyMustNotContain();
            case "replyMustContain" -> hard.replyMustContain();
            case "guardCodesExpected" -> hard.guardCodesExpected();
            case "mustCallAnyOf" -> hard.mustCallAnyOf();
            default -> null;
        };
        return value instanceof List<?> list ? !list.isEmpty() : value != null;
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
