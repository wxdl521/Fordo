package com.wenjin.eval.fixture;

import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.EvalScenarioDocument.MasteryOverride;
import com.wenjin.eval.scenario.EvalScenarioDocument.MistakeSeed;
import com.wenjin.eval.scenario.EvalScenarioDocument.Persona;
import com.wenjin.eval.scenario.EvalScenarioDocument.RosterStudent;
import com.wenjin.eval.scenario.EvalScenarioDocument.SnapshotSeed;

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
    private final EvalMistakePort mistakePort;
    private final EvalRosterPort rosterPort;

    public EvalFixture(EvalWorld world,
                       EvalMasteryPort masteryPort,
                       EvalReviewQueuePort reviewQueuePort,
                       EvalSessionPort sessionPort,
                       EvalNodeLookup nodeLookup) {
        this(world, masteryPort, reviewQueuePort, sessionPort, nodeLookup, EvalMistakePort.NONE);
    }

    public EvalFixture(EvalWorld world,
                       EvalMasteryPort masteryPort,
                       EvalReviewQueuePort reviewQueuePort,
                       EvalSessionPort sessionPort,
                       EvalNodeLookup nodeLookup,
                       EvalMistakePort mistakePort) {
        this(world, masteryPort, reviewQueuePort, sessionPort, nodeLookup, mistakePort, EvalRosterPort.NONE);
    }

    public EvalFixture(EvalWorld world,
                       EvalMasteryPort masteryPort,
                       EvalReviewQueuePort reviewQueuePort,
                       EvalSessionPort sessionPort,
                       EvalNodeLookup nodeLookup,
                       EvalMistakePort mistakePort,
                       EvalRosterPort rosterPort) {
        this.rosterPort = Objects.requireNonNull(rosterPort, "rosterPort");
        this.mistakePort = Objects.requireNonNull(mistakePort, "mistakePort");
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

        // 错题种子：列表靠前者更近（minutesAgo 递增），与「最近错题优先」一致
        List<String> appliedMistakes = new ArrayList<>();
        List<MistakeSeed> mistakes = persona.mistakes() == null ? List.of() : persona.mistakes();
        for (int i = 0; i < mistakes.size(); i++) {
            MistakeSeed seed = mistakes.get(i);
            if (seed == null || seed.nodeCode() == null || seed.nodeCode().isBlank()
                    || seed.misconceptionNodeCode() == null || seed.misconceptionNodeCode().isBlank()) {
                continue;
            }
            String code = seed.nodeCode().trim();
            String misconception = seed.misconceptionNodeCode().trim();
            Optional<Long> nodeId = nodeLookup.nodeIdByCode(world.courseId(), code);
            boolean misconceptionKnown = nodeLookup.nodeIdByCode(world.courseId(), misconception).isPresent();
            if (nodeId.isEmpty() || !misconceptionKnown) {
                String missing = nodeId.isEmpty() ? code : misconception;
                if (!missingNodes.contains(missing)) {
                    missingNodes.add(missing);
                }
                continue;
            }
            if (mistakePort.recordMistake(world.studentId(), world.courseId(), nodeId.get(), misconception, i + 1)) {
                appliedMistakes.add(code);
            }
        }

        return new PersonaInjectionResult(
                List.copyOf(appliedMastery),
                List.copyOf(appliedReview),
                List.copyOf(missingNodes),
                Map.copyOf(rawByCode),
                Map.copyOf(effectiveByCode),
                Map.copyOf(reviewQueuePort.forcedToday(world.studentId(), world.courseId())),
                List.copyOf(appliedMistakes));
    }

    /**
     * Convenience: reset then apply scenario persona.
     * <p>The class roster is scenario-scoped: any roster left by a previous scenario is removed first. Teacher
     * scenarios then run on the roster alone (the primary eval student is withdrawn from the course so it cannot
     * show up as an undiagnosed student) and start with clean assistant conversations/trace; student scenarios get
     * the primary student back in the course.
     */
    public PersonaInjectionResult prepareScenario(EvalScenarioDocument scenario) {
        Objects.requireNonNull(scenario, "scenario");
        resetStudentState();
        rosterPort.clearRoster();
        if (scenario.forTeacher()) {
            rosterPort.setPrimaryStudentEnrolled(world.studentId(), world.courseId(), false);
            rosterPort.clearTeacherState(world.teacherId(), world.courseId());
            List<RosterStudent> students = scenario.persona() == null || scenario.persona().roster() == null
                    ? List.of() : scenario.persona().roster();
            applyRoster(students);
        } else {
            rosterPort.setPrimaryStudentEnrolled(world.studentId(), world.courseId(), true);
        }
        return applyPersona(scenario.persona());
    }

    /**
     * Seed the class roster (teacher scenarios): one slot-addressed user per entry, enrolled in the eval course
     * (or only in the other eval course when {@code otherCourse}), with mastery, snapshot history, wrong/correct
     * answers and an optional private companion chat. All answers sit {@code lastPracticeDaysAgo} days back.
     */
    public RosterInjectionResult applyRoster(List<RosterStudent> students) {
        Map<String, Long> idsByName = new LinkedHashMap<>();
        List<String> missingNodes = new ArrayList<>();
        int slot = 0;
        for (RosterStudent s : students == null ? List.<RosterStudent>of() : students) {
            if (s == null || s.name() == null || s.name().isBlank()) {
                continue;
            }
            slot++;
            long studentId = rosterPort.ensureRosterUser(slot, s.name().trim());
            idsByName.put(s.name().trim(), studentId);
            if (Boolean.TRUE.equals(s.otherCourse())) {
                rosterPort.enrollInOtherCourse(studentId, world.teacherId());
                if (s.privateChat() != null && !s.privateChat().isBlank()) {
                    rosterPort.recordPrivateChat(studentId, world.courseId(), s.privateChat());
                }
                continue;
            }
            rosterPort.enroll(studentId, world.courseId());

            Long firstNode = null;
            for (MasteryOverride o : s.masteryOverrides() == null ? List.<MasteryOverride>of() : s.masteryOverrides()) {
                Optional<Long> nodeId = nodeLookup.nodeIdByCode(world.courseId(), o.nodeCode().trim());
                if (nodeId.isEmpty()) {
                    missingNodes.add(o.nodeCode().trim());
                    continue;
                }
                rosterPort.writeMastery(studentId, world.courseId(), nodeId.get(), o.raw());
                if (firstNode == null) {
                    firstNode = nodeId.get();
                }
            }
            for (SnapshotSeed snap : s.snapshots() == null ? List.<SnapshotSeed>of() : s.snapshots()) {
                Optional<Long> nodeId = nodeLookup.nodeIdByCode(world.courseId(), snap.nodeCode().trim());
                if (nodeId.isEmpty()) {
                    missingNodes.add(snap.nodeCode().trim());
                    continue;
                }
                rosterPort.writeSnapshot(studentId, world.courseId(), nodeId.get(), snap.score(), snap.daysAgo());
            }

            int baseMinutes = (s.lastPracticeDaysAgo() == null ? 1 : s.lastPracticeDaysAgo()) * 24 * 60;
            List<MistakeSeed> wrong = s.mistakes() == null ? List.of() : s.mistakes();
            for (int i = 0; i < wrong.size(); i++) {
                MistakeSeed m = wrong.get(i);
                Optional<Long> nodeId = nodeLookup.nodeIdByCode(world.courseId(), m.nodeCode().trim());
                Optional<Long> misconception = nodeLookup.nodeIdByCode(world.courseId(), m.misconceptionNodeCode().trim());
                if (nodeId.isEmpty() || misconception.isEmpty()) {
                    missingNodes.add(nodeId.isEmpty() ? m.nodeCode().trim() : m.misconceptionNodeCode().trim());
                    continue;
                }
                if (firstNode == null) {
                    firstNode = nodeId.get();
                }
                mistakePort.recordMistake(studentId, world.courseId(), nodeId.get(), m.misconceptionNodeCode().trim(),
                        baseMinutes + i + 1);
            }
            int correct = s.correctAnswers() == null ? 0 : s.correctAnswers();
            for (int j = 0; j < correct && firstNode != null; j++) {
                rosterPort.recordAnswer(studentId, world.courseId(), firstNode, true, baseMinutes + wrong.size() + j + 1);
            }
            if (s.privateChat() != null && !s.privateChat().isBlank()) {
                rosterPort.recordPrivateChat(studentId, world.courseId(), s.privateChat());
            }
        }
        return new RosterInjectionResult(Map.copyOf(idsByName), List.copyOf(missingNodes));
    }

    /** Result of {@link #applyRoster}: seeded user ids by real name and unknown node codes encountered. */
    public record RosterInjectionResult(Map<String, Long> studentIdsByName, List<String> missingNodeCodes) {
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
            Map<String, String> reviewPins,
            List<String> appliedMistakeCodes
    ) {
        public boolean hasMissingNodes() {
            return missingNodeCodes != null && !missingNodeCodes.isEmpty();
        }
    }
}
