package com.wenjin.service.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用真实子进程（当前 JDK 的 java + 测试 fixture）验证宿主侧进程调用。 */
class DefaultProcessRunnerTest {

    private final DefaultProcessRunner runner = new DefaultProcessRunner();

    private static List<String> fixture(String... args) throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        Path classes = Path.of(ProcessRunnerFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> cmd = new ArrayList<>(List.of(java, "-Xshare:auto", "-cp", classes.toString(),
                ProcessRunnerFixture.class.getName()));
        cmd.addAll(List.of(args));
        return cmd;
    }

    @Test
    @DisplayName("正常结束：退出码、stdout、stderr 分别回收")
    void normalRun() throws Exception {
        ProcessRunner.Result r = runner.run(fixture("stderr", "warn-text"), Duration.ofSeconds(30), 1000);
        assertThat(r.timedOut()).isFalse();
        assertThat(r.exitCode()).isZero();
        assertThat(new String(r.stdout(), StandardCharsets.UTF_8)).isEqualTo("out");
        assertThat(r.stderr()).contains("warn-text");
        assertThat(r.stdoutTruncated()).isFalse();
    }

    @Test
    @DisplayName("stdout 超上限：只保留前 N 字节并标记截断（其余读走丢弃，子进程不被写阻塞）")
    void stdoutCap() throws Exception {
        ProcessRunner.Result r = runner.run(fixture("print", "500000"), Duration.ofSeconds(30), 1000);
        assertThat(r.exitCode()).isZero();
        assertThat(r.stdout()).hasSize(1000);
        assertThat(r.stdoutTruncated()).isTrue();
    }

    @Test
    @DisplayName("超时：强制结束并返回 timedOut，退出码 -1")
    void timeout() throws Exception {
        long t0 = System.nanoTime();
        ProcessRunner.Result r = runner.run(fixture("sleep", "60000"), Duration.ofMillis(1500), 1000);
        assertThat(r.timedOut()).isTrue();
        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("非零退出码原样返回；命令不存在抛 IOException")
    void exitCodeAndMissingBinary() throws Exception {
        assertThat(runner.run(fixture("exit", "7"), Duration.ofSeconds(30), 10).exitCode()).isEqualTo(7);
        assertThatThrownBy(() -> runner.run(List.of("definitely-not-a-binary-wj-" + System.nanoTime()),
                Duration.ofSeconds(5), 10)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("stderr 上限可指定：超出读走丢弃并标记 stderrTruncated；默认 4096")
    void stderrCap() throws Exception {
        ProcessRunner.Result r = runner.run(fixture("eprint", "300000"), Duration.ofSeconds(30), 10, 2000);
        assertThat(r.exitCode()).isZero();
        assertThat(r.stderr()).hasSize(2000);
        assertThat(r.stderrTruncated()).isTrue();

        ProcessRunner.Result d = runner.run(fixture("eprint", "10000"), Duration.ofSeconds(30), 10);
        assertThat(d.stderr()).hasSize(ProcessRunner.DEFAULT_STDERR_CAP);
        ProcessRunner.Result small = runner.run(fixture("eprint", "100"), Duration.ofSeconds(30), 10, 2000);
        assertThat(small.stderrTruncated()).isFalse();
    }

    @Test
    @DisplayName("elapsedMs 是宿主测得的墙钟（含超时被杀的情况）")
    void elapsedIsMeasured() throws Exception {
        ProcessRunner.Result r = runner.run(fixture("sleep", "700"), Duration.ofSeconds(30), 10);
        assertThat(r.elapsedMs()).isBetween(650L, 25_000L);
        ProcessRunner.Result t = runner.run(fixture("sleep", "60000"), Duration.ofMillis(1200), 10);
        assertThat(t.timedOut()).isTrue();
        assertThat(t.elapsedMs()).isBetween(1150L, 25_000L);
    }
}
