package com.wenjin.service.code;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * 外部进程调用（docker CLI）。抽成接口以便单测断言命令行、模拟输出与超时。
 */
public interface ProcessRunner {

    /** stderr 只用于日志时保留的默认字节数 */
    int DEFAULT_STDERR_CAP = 4096;

    /**
     * 运行命令并等待结束；超过 {@code timeout} 强制结束进程并返回 {@code timedOut=true}。
     * 标准输入立即关闭。
     *
     * @param maxStdoutBytes stdout 最多保留的字节数，超出部分读走丢弃并置 {@code stdoutTruncated}
     * @param maxStderrBytes stderr 同理（{@code stderrTruncated}）
     */
    Result run(List<String> command, Duration timeout, int maxStdoutBytes, int maxStderrBytes)
            throws IOException, InterruptedException;

    /** stderr 只保留 {@link #DEFAULT_STDERR_CAP} 字节（只用于日志）。 */
    default Result run(List<String> command, Duration timeout, int maxStdoutBytes)
            throws IOException, InterruptedException {
        return run(command, timeout, maxStdoutBytes, DEFAULT_STDERR_CAP);
    }

    /**
     * @param exitCode        超时时为 -1
     * @param stderr          截断后的标准错误（UTF-8 解码）
     * @param elapsedMs       宿主侧测得的墙钟耗时（进程启动 → 结束/被杀），毫秒
     */
    record Result(int exitCode, byte[] stdout, boolean stdoutTruncated, String stderr, boolean timedOut,
                  boolean stderrTruncated, long elapsedMs) {
        public Result {
            stdout = stdout == null ? new byte[0] : stdout;
            stderr = stderr == null ? "" : stderr;
            elapsedMs = Math.max(0, elapsedMs);
        }

        public Result(int exitCode, byte[] stdout, boolean stdoutTruncated, String stderr, boolean timedOut) {
            this(exitCode, stdout, stdoutTruncated, stderr, timedOut, false, 0);
        }
    }
}
