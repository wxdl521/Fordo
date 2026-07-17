package com.wenjin.service.trace;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次 agent run 的显式上下文（禁止 ThreadLocal 跨线程传递）。
 * <p>toggle 关闭或写入失败时使用共享 {@link #NOOP}，调用点无需判空。
 */
public final class TraceContext {

    /** 共享空实现：step / finish 均为 no-op。 */
    public static final TraceContext NOOP = new TraceContext(0L, true, Instant.EPOCH);

    private final long runId;
    private final boolean noop;
    private final Instant startedAt;
    private final AtomicInteger seq = new AtomicInteger(0);
    private final AtomicInteger tokensIn = new AtomicInteger(0);
    private final AtomicInteger tokensOut = new AtomicInteger(0);
    private final Map<String, ToolCacheEntry> toolCache = new ConcurrentHashMap<>();

    private TraceContext(long runId, boolean noop, Instant startedAt) {
        this.runId = runId;
        this.noop = noop;
        this.startedAt = startedAt == null ? Instant.now() : startedAt;
    }

    /** 创建活跃上下文（由 {@link AgentTraceService} 在 run 建成功后调用）。 */
    static TraceContext active(long runId) {
        return new TraceContext(runId, false, Instant.now());
    }

    public long runId() {
        return runId;
    }

    public boolean isNoop() {
        return noop;
    }

    public Instant startedAt() {
        return startedAt;
    }

    /** 自增并返回下一个 seq（从 1 起）。 */
    public int nextSeq() {
        return seq.incrementAndGet();
    }

    /** 当前已分配的最大 seq（未分配则为 0）。 */
    public int currentSeq() {
        return seq.get();
    }

    public void addTokens(int in, int out) {
        if (in > 0) {
            tokensIn.addAndGet(in);
        }
        if (out > 0) {
            tokensOut.addAndGet(out);
        }
    }

    public int tokensIn() {
        return tokensIn.get();
    }

    public int tokensOut() {
        return tokensOut.get();
    }

    /** Phase 3 工具结果缓存；本期仅预留结构。 */
    public Map<String, ToolCacheEntry> toolCache() {
        return toolCache;
    }
}
