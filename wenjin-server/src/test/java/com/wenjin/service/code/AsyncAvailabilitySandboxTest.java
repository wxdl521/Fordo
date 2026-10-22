package com.wenjin.service.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncAvailabilitySandboxTest {

    /** available() 可编排、可阻塞的假后端。 */
    static final class ProbeBackend implements CodeSandbox {
        final AtomicInteger probes = new AtomicInteger();
        volatile boolean value;
        volatile RuntimeException fail;
        volatile CountDownLatch block;
        final CountDownLatch probed = new CountDownLatch(1);

        @Override
        public String name() {
            return "probe";
        }

        @Override
        public boolean available() {
            probes.incrementAndGet();
            try {
                if (block != null) {
                    block.await(10, TimeUnit.SECONDS);
                }
                if (fail != null) {
                    throw fail;
                }
                return value;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                probed.countDown();
            }
        }

        @Override
        public SandboxResult execute(SandboxRequest request) {
            return SandboxResult.failed("probe-backend");
        }
    }

    @Test
    @DisplayName("available() 只读缓存：从不调用后端探测；refresh 后才变化；探测异常视为不可用")
    void availableReadsCachedValue() {
        ProbeBackend backend = new ProbeBackend();
        backend.value = true;
        try (AsyncAvailabilitySandbox s = new AsyncAvailabilitySandbox(backend, Duration.ofSeconds(30))) {
            assertThat(s.available()).isFalse(); // 未探测前不可用
            assertThat(backend.probes).hasValue(0);

            s.refresh();
            assertThat(s.available()).isTrue();
            assertThat(s.available()).isTrue();
            assertThat(backend.probes).hasValue(1);

            backend.fail = new IllegalStateException("boom");
            s.refresh();
            assertThat(s.available()).isFalse();

            backend.fail = null;
            backend.value = false;
            s.refresh();
            assertThat(s.available()).isFalse();
            assertThat(s.name()).isEqualTo("probe");
            assertThat(s.execute(new SandboxRequest("x", List.of(), DockerRunnerScriptTest.LIMITS)).detail())
                    .isEqualTo("probe-backend");
        }
    }

    @Test
    @DisplayName("start()：启动即在后台探测；探测阻塞期间请求路径照常立即返回")
    void startProbesInBackgroundWithoutBlocking() throws Exception {
        ProbeBackend backend = new ProbeBackend();
        backend.value = true;
        backend.block = new CountDownLatch(1);
        try (AsyncAvailabilitySandbox s = new AsyncAvailabilitySandbox(backend, Duration.ofSeconds(30))) {
            s.start();
            long t0 = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                assertThat(s.available()).isFalse();
            }
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(1));
            backend.block.countDown();
            assertThat(backend.probed.await(10, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!s.available() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(s.available()).isTrue();
            assertThat(s.probed()).isTrue();
        }
    }

    @Test
    @DisplayName("按间隔周期刷新")
    void refreshesPeriodically() throws Exception {
        ProbeBackend backend = new ProbeBackend();
        try (AsyncAvailabilitySandbox s = new AsyncAvailabilitySandbox(backend, Duration.ofMillis(50))) {
            s.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (backend.probes.get() < 3 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(backend.probes.get()).isGreaterThanOrEqualTo(3);
        }
    }
}
