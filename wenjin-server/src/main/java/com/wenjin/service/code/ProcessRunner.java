package com.wenjin.service.code;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * 外部进程调用（docker CLI）。抽成接口以便单测断言命令行、模拟输出与超时。
 */
public interface ProcessRunner {

    /**
     * 运行命令并等待结束；超过 {@code timeout} 强制结束进程并返回 {@code timedOut=true}。
     * 标准输入立即关闭。
     *
     * @param maxStdoutBytes stdout 最多保留的字节数，超出部分读走丢弃并置 {@code stdoutTruncated}
     */
    Result run(List<String> command, Duration timeout, int maxStdoutBytes) throws IOException, InterruptedException;

    /**
     * @param exitCode 超时时为 -1
     * @param stderr   截断后的标准错误（只用于日志）
     */
    record Result(int exitCode, byte[] stdout, boolean stdoutTruncated, String stderr, boolean timedOut) {
        public Result {
            stdout = stdout == null ? new byte[0] : stdout;
            stderr = stderr == null ? "" : stderr;
        }
    }
}
