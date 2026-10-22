package com.wenjin.eval.runner;

import com.wenjin.dto.CompanionChatRequest;
import com.wenjin.eval.collect.AgentTraceReader;
import com.wenjin.eval.collect.SyncReplyCollector;
import com.wenjin.eval.hard.EvalStepView;
import com.wenjin.service.CompanionService;

import java.util.List;
import java.util.Objects;

/**
 * Real {@link CompanionTurnRunner}: drives one scripted student message through the production companion
 * service ({@code startTurn} → {@code streamReply}) with a synchronous collector that timestamps the first
 * token, then reads back the {@code agent_run}/{@code agent_step} rows the turn wrote.
 */
public final class ServiceCompanionTurnRunner implements CompanionTurnRunner {

    private final CompanionService companionService;
    private final AgentTraceReader traceReader;
    private final TurnAware turnAware; // nullable

    public ServiceCompanionTurnRunner(CompanionService companionService,
                                      AgentTraceReader traceReader,
                                      TurnAware turnAware) {
        this.companionService = Objects.requireNonNull(companionService, "companionService");
        this.traceReader = Objects.requireNonNull(traceReader, "traceReader");
        this.turnAware = turnAware;
    }

    @Override
    public TurnResult runTurn(TurnRequest request) {
        Objects.requireNonNull(request, "request");
        SyncReplyCollector collector = new SyncReplyCollector();

        CompanionChatRequest chat = new CompanionChatRequest();
        chat.setStudentId(request.studentId());
        chat.setCourseId(request.courseId());
        chat.setConversationId(request.conversationId());
        chat.setMessage(request.message());
        Long conversationId = companionService.startTurn(chat);

        long before = traceReader.maxRunId(conversationId);
        if (turnAware != null) {
            turnAware.beginTurn();
        }
        companionService.streamReply(conversationId, collector);
        long totalMs = collector.elapsedMs();

        List<Long> runIds = traceReader.runIdsAfter(conversationId, before);
        List<EvalStepView> steps = traceReader.steps(runIds);
        Long runId = runIds.isEmpty() ? null : runIds.get(runIds.size() - 1);
        return new TurnResult(conversationId, runId, collector.result(), steps, collector.ttftMs(), totalMs);
    }
}
