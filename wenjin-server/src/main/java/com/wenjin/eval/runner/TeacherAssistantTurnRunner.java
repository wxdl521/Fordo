package com.wenjin.eval.runner;

import com.wenjin.eval.collect.AgentTraceReader;
import com.wenjin.eval.collect.SyncReplyCollector;
import com.wenjin.eval.hard.EvalStepView;
import com.wenjin.service.assistant.TeacherAssistantService;
import com.wenjin.service.companion.ToolContext;

import java.util.List;
import java.util.Objects;

/**
 * {@link CompanionTurnRunner} for the teacher AI assistant: drives one scripted teacher message through the
 * production {@link TeacherAssistantService} ({@code startTurn} → {@code streamReply}) with the same synchronous
 * collector and trace read-back as {@link ServiceCompanionTurnRunner}. The request's acting user is the teacher;
 * the tool context is built the way the controller does after authorising the course
 * ({@link ToolContext#forTeacher}).
 */
public final class TeacherAssistantTurnRunner implements CompanionTurnRunner {

    private final TeacherAssistantService assistantService;
    private final AgentTraceReader traceReader;
    private final TurnAware turnAware; // nullable

    public TeacherAssistantTurnRunner(TeacherAssistantService assistantService,
                                      AgentTraceReader traceReader,
                                      TurnAware turnAware) {
        this.assistantService = Objects.requireNonNull(assistantService, "assistantService");
        this.traceReader = Objects.requireNonNull(traceReader, "traceReader");
        this.turnAware = turnAware;
    }

    @Override
    public TurnResult runTurn(TurnRequest request) {
        Objects.requireNonNull(request, "request");
        long teacherId = request.userId();
        long courseId = request.courseId();
        SyncReplyCollector collector = new SyncReplyCollector();

        Long conversationId = assistantService.startTurn(teacherId, courseId, request.conversationId(),
                request.message());

        String kind = TeacherAssistantService.TRACE_KIND;
        long before = traceReader.maxRunId(conversationId, kind);
        if (turnAware != null) {
            turnAware.beginTurn();
        }
        assistantService.streamReply(teacherId, conversationId, ToolContext.forTeacher(teacherId, courseId), collector);
        long totalMs = collector.elapsedMs();

        List<Long> runIds = traceReader.runIdsAfter(conversationId, before, kind);
        List<EvalStepView> steps = traceReader.steps(runIds);
        Long runId = runIds.isEmpty() ? null : runIds.get(runIds.size() - 1);
        return new TurnResult(conversationId, runId, collector.result(), steps, collector.ttftMs(), totalMs);
    }
}
