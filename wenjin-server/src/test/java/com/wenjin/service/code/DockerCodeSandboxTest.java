package com.wenjin.service.code;

import com.wenjin.config.CodeCheckProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.wenjin.service.code.DockerRunnerScriptTest.b64;
import static com.wenjin.service.code.DockerRunnerScriptTest.out;
import static org.assertj.core.api.Assertions.assertThat;

class DockerCodeSandboxTest {

    private static final String SOURCE = "public class Main { public static void main(String[] a) { System.out.println(3); } }";

    @TempDir
    Path tmp;

    private CodeCheckProperties.Docker cfg;
    private FakeRunner runner;
    private MutableClock clock;
    private DockerCodeSandbox sandbox;

    @BeforeEach
    void setUp() {
        cfg = new CodeCheckProperties.Docker();
        cfg.setWorkDir(tmp.toString());
        runner = new FakeRunner();
        clock = new MutableClock(Instant.parse("2026-10-22T08:00:00Z"));
        sandbox = new DockerCodeSandbox(cfg, runner, clock);
    }

    private SandboxResult exec(List<String> stdins) {
        return sandbox.execute(new SandboxRequest(SOURCE, stdins, DockerRunnerScriptTest.LIMITS));
    }

    // ───────────────────────── 命令行安全参数 ─────────────────────────

    @Test
    @DisplayName("docker run 命令行逐项包含全部安全参数")
    void runCommand_hasEverySecurityFlag() {
        runner.respond(out("@@WJ1 BEGIN", "@@WJ1 COMPILE 0 -", "@@WJ1 CASE 0 0 10 " + b64("3\n") + " -", "@@WJ1 END"));
        exec(List.of(""));

        List<String> cmd = runner.calls.get(0);
        assertThat(cmd.subList(0, 2)).containsExactly("docker", "run");
        assertFlag(cmd, "--rm");
        assertFlag(cmd, "--read-only");
        assertPair(cmd, "--network", "none");
        assertPair(cmd, "--cpus", "1.0");
        assertPair(cmd, "--memory", "256m");
        assertPair(cmd, "--memory-swap", "256m");
        assertPair(cmd, "--pids-limit", "64");
        assertPair(cmd, "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777");
        assertPair(cmd, "--cap-drop", "ALL");
        assertPair(cmd, "--security-opt", "no-new-privileges");
        assertPair(cmd, "--user", "65534:65534");
        assertPair(cmd, "--pull", "never");
        assertPair(cmd, "--ulimit", "core=0:0");
        assertPair(cmd, "--entrypoint", "timeout");
        assertThat(cmd.get(cmd.indexOf("--name") + 1)).matches("wj-code-[0-9a-f]{32}");
        String mount = cmd.get(cmd.indexOf("--mount") + 1);
        assertThat(mount).startsWith("type=bind,source=").endsWith(",target=/work,readonly");
        // 镜像之后只有「PID 1 = timeout 自限寿命」+ runner 脚本：预算 15 + 1×(3+1) = 19，再加 10s 余量
        assertThat(cmd.subList(cmd.size() - 6, cmd.size())).containsExactly(
                "eclipse-temurin:21-jdk", "-s", "KILL", "29", "/bin/sh", "/work/run.sh");
        // 绝无放宽隔离的参数
        assertThat(cmd).noneMatch(a -> a.startsWith("--privileged") || a.startsWith("--cap-add")
                || a.equals("host") || a.startsWith("--device") || a.contains("docker.sock")
                || a.startsWith("--volume") || a.equals("-v"));
    }

    @Test
    @DisplayName("限额改动反映到命令行；宿主墙钟 = 编译 + 用例×(运行+1) + 余量")
    void runCommand_followsLimits() {
        runner.respond(out("@@WJ1 BEGIN", "@@WJ1 COMPILE 0 -", "@@WJ1 END"));
        cfg.setBinary("/usr/bin/docker");
        cfg.setImage("my/jdk:21");
        sandbox.execute(new SandboxRequest(SOURCE, List.of("a", "b"),
                new SandboxLimits(10, 2, 512, 200, 0.5, 32, 1000)));
        List<String> cmd = runner.calls.get(0);
        assertThat(cmd.get(0)).isEqualTo("/usr/bin/docker");
        assertPair(cmd, "--cpus", "0.5");
        assertPair(cmd, "--memory", "512m");
        assertPair(cmd, "--memory-swap", "512m");
        assertPair(cmd, "--pids-limit", "32");
        assertThat(cmd.subList(cmd.size() - 6, cmd.size())).containsExactly(
                "my/jdk:21", "-s", "KILL", String.valueOf(10 + 2 * (2 + 1) + DockerCodeSandbox.IN_CONTAINER_MARGIN_SECONDS),
                "/bin/sh", "/work/run.sh");
        assertThat(runner.timeouts.get(0)).isEqualTo(Duration.ofSeconds(10 + 2 * (2 + 1) + DockerCodeSandbox.STARTUP_SLACK_SECONDS));
        assertThat(runner.caps.get(0)).isEqualTo(DockerRunnerScript.hostStdoutCap(
                new SandboxLimits(10, 2, 512, 200, 0.5, 32, 1000), 2));
    }

    // ───────────────────────── 挂载目录内容 ─────────────────────────

    @Test
    @DisplayName("挂载目录：Main.java + in/<i>.txt + run.sh（UTF-8、LF）；执行后删除")
    void workDir_writtenThenDeleted() {
        AtomicReference<Path> seen = new AtomicReference<>();
        runner.onRun = cmd -> {
            String mount = cmd.get(cmd.indexOf("--mount") + 1);
            Path dir = Path.of(mount.substring("type=bind,source=".length(), mount.indexOf(",target=")));
            seen.set(dir);
            try {
                assertThat(Files.readString(dir.resolve("Main.java"))).isEqualTo(SOURCE);
                assertThat(Files.readString(dir.resolve("in/0.txt"))).isEqualTo("1 2\n");
                assertThat(Files.readString(dir.resolve("in/1.txt"))).isEqualTo("中文");
                String script = Files.readString(dir.resolve("run.sh"));
                assertThat(script).contains("N=2\n").doesNotContain("\r");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        };
        runner.respond(out("@@WJ1 BEGIN", "@@WJ1 COMPILE 0 -",
                "@@WJ1 CASE 0 0 5 " + b64("3") + " -", "@@WJ1 CASE 1 0 5 " + b64("ok") + " -", "@@WJ1 END"));

        SandboxResult r = exec(List.of("1 2\n", "中文"));

        assertThat(r.status()).isEqualTo(SandboxResult.Status.COMPLETED);
        assertThat(r.cases()).hasSize(2);
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get()).doesNotExist();
        assertThat(seen.get().getParent()).isEqualTo(tmp);
    }

    // ───────────────────────── 结果映射 ─────────────────────────

    @Test
    @DisplayName("编译失败 / 编译超时 映射")
    void compileOutcomes() {
        runner.respond(out("@@WJ1 BEGIN", "@@WJ1 COMPILE 1 " + b64("/work/Main.java:1: error: x"), "@@WJ1 END"));
        SandboxResult ce = exec(List.of(""));
        assertThat(ce.status()).isEqualTo(SandboxResult.Status.COMPILE_ERROR);
        assertThat(ce.compileOutput()).contains("error: x");

        runner.respond(out("@@WJ1 BEGIN", "@@WJ1 COMPILE 124 -", "@@WJ1 END"));
        assertThat(exec(List.of("")).status()).isEqualTo(SandboxResult.Status.COMPILE_TIMEOUT);
    }

    @Test
    @DisplayName("宿主墙钟超时：docker rm -f 强制销毁容器，返回 TIMEOUT（保留已完成用例）")
    void hostTimeout_killsContainer() {
        runner.respondTimeout(out("@@WJ1 BEGIN", "@@WJ1 COMPILE 0 -", "@@WJ1 CASE 0 0 5 " + b64("3") + " -"));
        SandboxResult r = exec(List.of("", ""));
        assertThat(r.status()).isEqualTo(SandboxResult.Status.TIMEOUT);
        assertThat(r.cases()).hasSize(1);
        String name = runner.calls.get(0).get(runner.calls.get(0).indexOf("--name") + 1);
        assertThat(runner.calls.get(1)).containsExactly("docker", "rm", "-f", name);
    }

    @Test
    @DisplayName("docker 自身失败（缺镜像 / 守护进程不通）、FATAL、未编译即中断 → FAILED")
    void infrastructureFailures() {
        runner.respond(125, "Unable to find image".getBytes(StandardCharsets.UTF_8));
        assertThat(exec(List.of("")).status()).isEqualTo(SandboxResult.Status.FAILED);

        runner.respond(out("@@WJ1 BEGIN", "@@WJ1 FATAL missing-timeout"));
        SandboxResult fatal = exec(List.of(""));
        assertThat(fatal.status()).isEqualTo(SandboxResult.Status.FAILED);
        assertThat(fatal.detail()).contains("missing-timeout");

        runner.respond(out("@@WJ1 BEGIN"));
        assertThat(exec(List.of("")).status()).isEqualTo(SandboxResult.Status.FAILED);

        runner.fail = new IOException("docker: not found");
        assertThat(exec(List.of("")).status()).isEqualTo(SandboxResult.Status.FAILED);
    }

    @Test
    @DisplayName("编译后运行器中途被破坏（无 END）：已有用例照常返回，缺失用例由上层判运行错误")
    void partialRun_isCompleted() {
        runner.respond(out("@@WJ1 BEGIN", "@@WJ1 COMPILE 0 -", "@@WJ1 CASE 0 0 5 " + b64("3") + " -"));
        SandboxResult r = exec(List.of("", ""));
        assertThat(r.status()).isEqualTo(SandboxResult.Status.COMPLETED);
        assertThat(r.cases()).hasSize(1);
    }

    @Test
    @DisplayName("容器内 PID 1（timeout）寿命到期、协议未结束 → TIMEOUT")
    void inContainerLifetime_isTimeout() {
        runner.respond(124, out("@@WJ1 BEGIN", "@@WJ1 COMPILE 0 -", "@@WJ1 CASE 0 0 5 " + b64("3") + " -"));
        SandboxResult r = exec(List.of("", ""));
        assertThat(r.status()).isEqualTo(SandboxResult.Status.TIMEOUT);
        assertThat(r.cases()).hasSize(1);
    }

    @Test
    @DisplayName("中断：销毁容器并恢复中断标记")
    void interrupted_killsAndRestoresFlag() {
        runner.interrupt = true;
        SandboxResult r = exec(List.of(""));
        assertThat(r.status()).isEqualTo(SandboxResult.Status.FAILED);
        assertThat(Thread.interrupted()).isTrue();
        assertThat(runner.calls.get(1).subList(0, 3)).containsExactly("docker", "rm", "-f");
    }

    @Test
    @DisplayName("工作目录父路径含逗号（会破坏 --mount 解析）→ FAILED，不调用 docker")
    void commaInWorkDir_rejected() throws IOException {
        Path bad = Files.createDirectories(tmp.resolve("a,b"));
        cfg.setWorkDir(bad.toString());
        assertThat(exec(List.of("")).status()).isEqualTo(SandboxResult.Status.FAILED);
        assertThat(runner.calls).isEmpty();
    }

    // ───────────────────────── available ─────────────────────────

    @Test
    @DisplayName("available：docker version 与 image inspect 都成功才可用；结果缓存，过期后重查")
    void available_cached() {
        runner.respond(0, "27.3.1\n".getBytes(StandardCharsets.UTF_8));
        runner.respond(0, "sha256:abc\n".getBytes(StandardCharsets.UTF_8));
        assertThat(sandbox.available()).isTrue();
        assertThat(runner.calls.get(0)).containsExactly("docker", "version", "--format", "{{.Server.Version}}");
        assertThat(runner.calls.get(1)).containsExactly("docker", "image", "inspect", "--format", "{{.Id}}",
                "eclipse-temurin:21-jdk");

        assertThat(sandbox.available()).isTrue();
        assertThat(runner.calls).hasSize(2);

        clock.advance(Duration.ofSeconds(31));
        runner.respond(1, new byte[0]); // 守护进程挂了
        assertThat(sandbox.available()).isFalse();
        assertThat(runner.calls).hasSize(3);
    }

    @Test
    @DisplayName("available：镜像缺失 / docker 不存在 / 超时 → false，不抛")
    void available_failures() {
        runner.respond(0, "27.3.1".getBytes(StandardCharsets.UTF_8));
        runner.respond(1, new byte[0]);
        assertThat(sandbox.available()).isFalse();

        clock.advance(Duration.ofMinutes(5));
        runner.fail = new IOException("CreateProcess error=2");
        assertThat(sandbox.available()).isFalse();

        clock.advance(Duration.ofMinutes(5));
        runner.fail = null;
        runner.respondTimeout(new byte[0]);
        assertThat(sandbox.available()).isFalse();
    }

    // ───────────────────────── helpers ─────────────────────────

    private static void assertFlag(List<String> cmd, String flag) {
        assertThat(cmd).as("flag " + flag).contains(flag);
    }

    private static void assertPair(List<String> cmd, String flag, String value) {
        boolean found = false;
        for (int i = 0; i < cmd.size() - 1; i++) {
            if (cmd.get(i).equals(flag) && cmd.get(i + 1).equals(value)) {
                found = true;
                break;
            }
        }
        assertThat(found).as(flag + " " + value + " in " + cmd).isTrue();
    }

    /** 记录命令、按队列回放结果的假 ProcessRunner。 */
    static final class FakeRunner implements ProcessRunner {
        final List<List<String>> calls = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();
        final List<Integer> caps = new ArrayList<>();
        final List<Result> queue = new ArrayList<>();
        java.util.function.Consumer<List<String>> onRun;
        IOException fail;
        boolean interrupt;

        void respond(byte[] stdout) {
            respond(0, stdout);
        }

        void respond(int exit, byte[] stdout) {
            queue.add(new Result(exit, stdout, false, "", false));
        }

        void respondTimeout(byte[] stdout) {
            queue.add(new Result(-1, stdout, false, "", true));
        }

        @Override
        public Result run(List<String> command, Duration timeout, int maxStdoutBytes)
                throws IOException, InterruptedException {
            calls.add(List.copyOf(command));
            timeouts.add(timeout);
            caps.add(maxStdoutBytes);
            boolean isRun = command.size() > 1 && command.get(1).equals("run");
            if (isRun && onRun != null) {
                onRun.accept(command);
            }
            if (fail != null) {
                throw fail;
            }
            if (interrupt && isRun) {
                throw new InterruptedException("test");
            }
            if (queue.isEmpty()) {
                return new Result(0, new byte[0], false, "", false);
            }
            return queue.remove(0);
        }
    }
}
