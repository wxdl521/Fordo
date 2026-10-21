package com.wenjin.eval.integration;

import com.wenjin.ai.CompanionAiClient;
import com.wenjin.eval.golden.GoldenFile;
import com.wenjin.eval.golden.GoldenLoader;
import com.wenjin.eval.scenario.EvalScenarioDocument;
import com.wenjin.eval.scenario.ScenarioLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Stateless stand-in for a real model in the LIVE pipeline test: it answers from the classpath goldens,
 * keyed by the latest user message in the request; the round within the turn is the number of assistant
 * tool-call messages after that user message. Reports real-looking usage so the token path is exercised.
 */
final class GoldenScriptedCompanionAiClient implements CompanionAiClient {

    private final Map<String, GoldenFile.GoldenTurn> byUserMessage = new HashMap<>();

    GoldenScriptedCompanionAiClient() {
        GoldenLoader loader = new GoldenLoader();
        for (EvalScenarioDocument scenario : new ScenarioLoader().loadAll()) {
            if ("S18".equals(scenario.code())) {
                continue;
            }
            GoldenFile golden = loader.loadFromClasspath(scenario.code());
            for (int i = 0; i < scenario.script().size() && i < golden.turns().size(); i++) {
                byUserMessage.put(scenario.script().get(i).user(), golden.turns().get(i));
            }
        }
    }

    @Override
    public StreamTurnResult streamTurn(List<Map<String, Object>> messages,
                                       List<Map<String, Object>> toolSchemas,
                                       ToolChoice toolChoice,
                                       Consumer<String> onContentDelta) {
        int lastUser = -1;
        for (int i = 0; i < messages.size(); i++) {
            if ("user".equals(messages.get(i).get("role"))) {
                lastUser = i;
            }
        }
        if (lastUser < 0) {
            throw new IllegalStateException("stub: no user message");
        }
        String user = String.valueOf(messages.get(lastUser).get("content"));
        GoldenFile.GoldenTurn turn = byUserMessage.get(user);
        if (turn == null) {
            turn = byUserMessage.entrySet().stream()
                    .filter(e -> user.contains(e.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("stub: no golden for user message: " + user));
        }
        int round = 0;
        for (int i = lastUser + 1; i < messages.size(); i++) {
            Map<String, Object> m = messages.get(i);
            if ("assistant".equals(m.get("role")) && m.containsKey("tool_calls")) {
                round++;
            }
        }
        if (round >= turn.rounds().size()) {
            throw new IllegalStateException("stub: golden has no round " + round + " for: " + user);
        }
        GoldenFile.GoldenRound r = turn.rounds().get(round);
        String content = r.content();
        int half = content.length() / 2;
        if (!content.isEmpty()) {
            onContentDelta.accept(content.substring(0, half));
            onContentDelta.accept(content.substring(half));
        }
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < r.toolCalls().size(); i++) {
            GoldenFile.GoldenToolCall c = r.toolCalls().get(i);
            calls.add(new ToolCall("stub-" + round + "-" + i, c.name(), c.arguments()));
        }
        int prompt = Objects.toString(messages, "").length() / 3;
        return new StreamTurnResult(content, calls, new Usage(prompt, Math.max(1, content.length()), null),
                r.finishReason());
    }

    @Override
    public void stream(String systemPrompt, List<ChatMsg> history, String userMessage, Consumer<String> onToken) {
        throw new IllegalStateException("stub: legacy stream not scripted");
    }

    @Override
    public TurnOutcome completeWithTools(List<Map<String, Object>> messages, List<Map<String, Object>> toolSchemas) {
        throw new IllegalStateException("stub: completeWithTools not scripted");
    }

    @Override
    public void streamMessages(List<Map<String, Object>> messages, Consumer<String> onToken) {
        throw new IllegalStateException("stub: streamMessages not scripted");
    }
}
