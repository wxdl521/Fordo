package com.wenjin.service.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 把后端的（阻塞的）可用性探测挪出请求路径：后台线程按固定间隔调用 {@code delegate.available()}，
 * {@link #available()} 只读最近一次结果，<b>从不阻塞</b>。启动后立即在后台做第一次探测；探测完成前视为不可用。
 *
 * <p>请求路径上的调用方：练习组卷（是否下发编程题）、判分/运行前检查、伴侣每轮工具 schema 过滤——过去每次都可能
 * 同步执行 {@code docker version} + {@code docker image inspect} 或 Judge0 {@code GET /about}（缓存过期时）。</p>
 */
public class AsyncAvailabilitySandbox implements CodeSandbox, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AsyncAvailabilitySandbox.class);

    private final CodeSandbox delegate;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private volatile boolean available;
    private volatile boolean probed;

    public AsyncAvailabilitySandbox(CodeSandbox delegate, Duration interval) {
        this.delegate = delegate;
        this.interval = interval.isNegative() || interval.isZero() ? Duration.ofSeconds(30) : interval;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wj-code-availability");
            t.setDaemon(true);
            return t;
        });
    }

    /** 启动后台刷新（首次立即执行）。 */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::refresh, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 同步探测一次并更新缓存值（后台线程与测试使用）。 */
    void refresh() {
        boolean value;
        try {
            value = delegate.available();
        } catch (RuntimeException e) {
            log.warn("代码沙箱可用性探测异常：{}", e.getClass().getSimpleName());
            value = false;
        }
        if (probed && value != available) {
            log.info("代码沙箱 {} 可用性变化：{} → {}", delegate.name(), available, value);
        }
        available = value;
        probed = true;
    }

    boolean probed() {
        return probed;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    /** 最近一次后台探测的结果；从不阻塞、从不调用 docker / 网络。 */
    @Override
    public boolean available() {
        return available;
    }

    @Override
    public SandboxResult execute(SandboxRequest request) {
        return delegate.execute(request);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
