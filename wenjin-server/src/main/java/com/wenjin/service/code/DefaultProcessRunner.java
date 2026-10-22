package com.wenjin.service.code;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 {@link ProcessBuilder} 的进程调用：stdout / stderr 由两个虚拟线程各自读走（带上限，超出部分读走丢弃，
 * 避免子进程写满管道阻塞），超时 {@link Process#destroyForcibly()}。不经过 shell，参数不做任何拼接/转义。
 */
public class DefaultProcessRunner implements ProcessRunner {

    /** stderr 只用于日志，保留这么多字节足够 */
    private static final int STDERR_CAP = 4096;
    private static final long READER_JOIN_MS = 2000;

    @Override
    public Result run(List<String> command, Duration timeout, int maxStdoutBytes)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // 子进程可能已退出
        }
        CappedReader out = new CappedReader(p.getInputStream(), Math.max(0, maxStdoutBytes));
        CappedReader err = new CappedReader(p.getErrorStream(), STDERR_CAP);
        Thread to = Thread.ofVirtual().name("wj-proc-out").start(out);
        Thread te = Thread.ofVirtual().name("wj-proc-err").start(err);
        boolean finished;
        try {
            finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            throw e;
        }
        if (!finished) {
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
        }
        to.join(READER_JOIN_MS);
        te.join(READER_JOIN_MS);
        return new Result(finished ? p.exitValue() : -1, out.bytes(), out.truncated(),
                new String(err.bytes(), StandardCharsets.UTF_8), !finished);
    }

    private static final class CappedReader implements Runnable {
        private final InputStream in;
        private final int cap;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final AtomicBoolean truncated = new AtomicBoolean();

        CappedReader(InputStream in, int cap) {
            this.in = in;
            this.cap = cap;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try (in) {
                int r;
                while ((r = in.read(chunk)) != -1) {
                    synchronized (buf) {
                        int room = cap - buf.size();
                        if (room > 0) {
                            buf.write(chunk, 0, Math.min(room, r));
                        }
                        if (r > room) {
                            truncated.set(true);
                        }
                    }
                }
            } catch (IOException ignored) {
                // 进程被杀时流关闭，正常
            }
        }

        byte[] bytes() {
            synchronized (buf) {
                return buf.toByteArray();
            }
        }

        boolean truncated() {
            return truncated.get();
        }
    }
}
