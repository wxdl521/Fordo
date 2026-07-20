package com.wenjin.eval.fixture;

import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.EvalScenarioDocument.MasteryOverride;
import com.wenjin.eval.scenario.EvalScenarioDocument.Persona;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Isolating eval fixture: reset student mutable state and inject persona mastery/review queue.
 * <p>Port-based so unit tests run without Docker; Spring adapters wire production mappers under
 * profile {@code eval}.
 */
public final class EvalFixture {

    private final EvalWorld world;
    private final EvalMasteryPort masteryPort;
    private final EvalReviewQueuePort reviewQueuePort;
    private final EvalSessionPort sessionPort;
    private final EvalNodeLookup nodeLookup;

    public EvalFixture(EvalWorld world,
                       EvalMasteryPort masteryPort,
                       EvalReviewQueuePort reviewQueuePort,
                       EvalSessionPort sessionPort,
                       EvalNodeLookup nodeLookup) {
        this.world = Objects.requireNonNull(world, "world");
        this.masteryPort = Objects.requireNonNull(masteryPort, "masteryPort");
        this.reviewQueuePort = Objects.requireNonNull(reviewQueuePort, "reviewQueuePort");
        this.sessionPort = Objects.requireNonNull(sessionPort, "sessionPort");
        this.nodeLookup = Objects.requireNonNull(nodeLookup, "nodeLookup");
    }

    public EvalWorld world() {
        return world;
    }

    /**
     * Reset mutable student state for the next scenario (mastery, review pins, sessions).
     * Graph / question bank are left intact.
     */
    public void resetStudentState() {
        long studentId = world.studentId();
        long courseId = world.courseId();
        masteryPort.clearMastery(studentId, courseId);
        reviewQueuePort.clearStudent(studentId, courseId);
        sessionPort.clearSessions(studentId, courseId);
    }

    /**
     * Apply persona after {@link #resetStudentState()}: mastery overrides + review queue pins.
     *
     * @return injection report for assertions
     */
    public PersonaInjectionResult applyPersona(Persona persona) {
        Objects.requireNonNull(persona, "persona");
        List<String> appliedMastery = new ArrayList<>();
        List<String> appliedReview = new ArrayList<>();
        List<String> missingNodes = new ArrayList<>();
        Map<String, Double> rawByCode = new LinkedHashMap<>();
        Map<String, Double> effectiveByCode = new LinkedHashMap<>();

        List<MasteryOverride> overrides = persona.masteryOverrides() == null
                ? List.of()
                : persona.masteryOverrides();
        for (MasteryOverride override : overrides) {
            if (override == null || override.nodeCode() == null || override.nodeCode().isBlank()) {
                continue;
            }
            String code = override.nodeCode().trim();
            Optional<Long> nodeId = nodeLookup.nodeIdByCode(world.courseId(), code);
            if (nodeId.isEmpty()) {
                missingNodes.add(code);
                continue;
            }
            double raw = override.raw() == null ? 0.0 : override.raw();
            masteryPort.writeMastery(
                    world.studentId(),
                    world.courseId(),
                    nodeId.get(),
                    raw,
                    override.effective());
            appliedMastery.add(code);
            Double storedRaw = masteryPort.readRaw01(world.studentId(), world.courseId(), nodeId.get());
            Double storedEff = masteryPort.readEffective01(world.studentId(), world.courseId(), nodeId.get());
            if (storedRaw != null) {
                rawByCode.put(code, storedRaw);
            }
            if (storedEff != null) {
                effectiveByCode.put(code, storedEff);
            }
        }

        List<String> queue = persona.reviewQueue() == null ? List.of() : persona.reviewQueue();
        List<String> toPin = new ArrayList<>();
        for (String code : queue) {
            if (code == null || code.isBlank()) {
                continue;
            }
            String trimmed = code.trim();
            Optional<Long> nodeId = nodeLookup.nodeIdByCode(world.courseId(), trimmed);
            if (nodeId.isEmpty()) {
                if (!missingNodes.contains(trimmed)) {
                    missingNodes.add(trimmed);
                }
                continue;
            }
            toPin.add(trimmed);
        }
        if (!toPin.isEmpty()) {
            reviewQueuePort.pinAll(world.studentId(), world.courseId(), toPin);
            appliedReview.addAll(toPin);
        }

        return new PersonaInjectionResult(
                List.copyOf(appliedMastery),
                List.copyOf(appliedReview),
                List.copyOf(missingNodes),
                Map.copyOf(rawByCode),
                Map.copyOf(effectiveByCode),
                Map.copyOf(reviewQueuePort.forcedToday(world.studentId(), world.courseId())));
    }

    /**
     * Convenience: reset then apply scenario persona.
     */
    public PersonaInjectionResult prepareScenario(EvalScenarioDocument scenario) {
        Objects.requireNonNull(scenario, "scenario");
        resetStudentState();
        return applyPersona(scenario.persona());
    }

    /**
     * Result of persona injection for unit/IT assertions.
     */
    public record PersonaInjectionResult(
            List<String> appliedMasteryCodes,
            List<String> appliedReviewCodes,
            List<String> missingNodeCodes,
            Map<String, Double> rawByCode,
            Map<String, Double> effectiveByCode,
            Map<String, String> reviewPins
    ) {
        public boolean hasMissingNodes() {
            return missingNodeCodes != null && !missingNodeCodes.isEmpty();
        }
    }
}
