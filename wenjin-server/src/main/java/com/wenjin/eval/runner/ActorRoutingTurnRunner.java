package com.wenjin.eval.runner;

import java.util.Objects;

/**
 * Picks the turn runner by the request's actor: student turns go through the learning companion, teacher turns
 * through the teacher assistant. Lets one executor/harness run both kinds of scenarios.
 */
public final class ActorRoutingTurnRunner implements CompanionTurnRunner {

    private final CompanionTurnRunner studentRunner;
    private final CompanionTurnRunner teacherRunner;

    public ActorRoutingTurnRunner(CompanionTurnRunner studentRunner, CompanionTurnRunner teacherRunner) {
        this.studentRunner = Objects.requireNonNull(studentRunner, "studentRunner");
        this.teacherRunner = Objects.requireNonNull(teacherRunner, "teacherRunner");
    }

    @Override
    public TurnResult runTurn(TurnRequest request) {
        Objects.requireNonNull(request, "request");
        return (request.isTeacher() ? teacherRunner : studentRunner).runTurn(request);
    }
}
