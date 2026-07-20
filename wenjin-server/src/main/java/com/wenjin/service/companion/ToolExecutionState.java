package com.wenjin.service.companion;

/**
 * 工具调用预算。
 * <p>
 * 当 {@code maxToolCalls >= 2} 时预留 1 次给 side-effecting 工具（如 {@code start_practice}），
 * 避免模型用尽只读查询配额后无法发起练习。
 * <p>
 * 另计 {@code record_observation} 每 run 写入次数（与总工具次数独立）。
 */
public final class ToolExecutionState {

    private final int maxToolCalls;
    private final int nonSideCap;
    private int used;
    private int nonSideUsed;
    private int memoryWrites;

    public ToolExecutionState(int maxToolCalls) {
        this.maxToolCalls = Math.max(0, maxToolCalls);
        // max>=2 时预留 1 槽给有副作用的工具
        int reserve = this.maxToolCalls >= 2 ? 1 : 0;
        this.nonSideCap = Math.max(0, this.maxToolCalls - reserve);
    }

    /**
     * @param sideEffecting true 表示会写库/开会话的工具（如 start_practice）
     * @return 是否仍有预算
     */
    public synchronized boolean tryConsume(boolean sideEffecting) {
        if (used >= maxToolCalls) {
            return false;
        }
        if (!sideEffecting) {
            if (nonSideUsed >= nonSideCap) {
                return false;
            }
            nonSideUsed++;
        }
        used++;
        return true;
    }

    /**
     * 记忆写工具（record_observation）每 run 配额；失败时不消耗总工具预算。
     *
     * @param maxPerRun 上限（≤0 表示禁止写入）
     * @return true 已占用一次写入配额
     */
    public synchronized boolean tryConsumeMemoryWrite(int maxPerRun) {
        int cap = Math.max(0, maxPerRun);
        if (memoryWrites >= cap) {
            return false;
        }
        memoryWrites++;
        return true;
    }

    /** @deprecated 兼容旧调用，按非副作用计费 */
    @Deprecated
    public synchronized boolean tryConsume() {
        return tryConsume(false);
    }

    public synchronized int used() {
        return used;
    }

    public synchronized int nonSideUsed() {
        return nonSideUsed;
    }

    public synchronized int memoryWrites() {
        return memoryWrites;
    }

    public int maxToolCalls() {
        return maxToolCalls;
    }

    public int nonSideCap() {
        return nonSideCap;
    }
}
