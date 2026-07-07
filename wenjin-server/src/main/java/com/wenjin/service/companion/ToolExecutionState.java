package com.wenjin.service.companion;

public final class ToolExecutionState {

    private final int maxToolCalls;
    private int used;

    public ToolExecutionState(int maxToolCalls) {
        this.maxToolCalls = Math.max(0, maxToolCalls);
    }

    public synchronized boolean tryConsume() {
        if (used >= maxToolCalls) {
            return false;
        }
        used++;
        return true;
    }

    public synchronized int used() {
        return used;
    }
}
