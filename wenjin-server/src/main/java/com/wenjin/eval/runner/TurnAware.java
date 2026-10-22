package com.wenjin.eval.runner;

/**
 * An eval-side {@code CompanionAiClient} layer that needs to know where a scripted user turn starts
 * (replay cursor / recording bucket). Called by the turn runner right before {@code streamReply}.
 */
public interface TurnAware {

    void beginTurn();
}
