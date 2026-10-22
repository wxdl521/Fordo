package com.wenjin.service.code;

import com.wenjin.config.CodeCheckProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class DockerCodeSandboxTest {

    private static final String SOURCE = "public class Main { public static void main(String[] a) { System.out.println(3); } }";
    private static final SandboxLimits LIMITS = DockerRunnerScriptTest.LIMITS; // 15s 编译 / 3s 运行 / 64KiB 输出

    @TempDir
    Path tmp;

    private CodeCheckProperties.Docker cfg;
    private FakeRunner runner;
    private AtomicLong nanos;
    private DockerCodeSandbox sandbox;

    @BeforeEach
    void setUp() {
        cfg = new CodeCheckProperties.Docker();
        cfg.setWorkDir(tmp.toString());
        runner = new FakeRunner();
        nanos = new AtomicLong();
        sandbox = new DockerCodeSandbox(cfg, runner, nanos::get);
    }

    private SandboxResult exec(List<String> stdins) {
        return sandbox.execute(new SandboxRequest(SOURCE, stdins, LIMITS));
    }

    private List<List<String>> runs() {
        return runner.calls.stream().filter(c -> c.size() > 1 && c.get(1).equals("run")).toList();
    }

    // ───────────────────────── 命令行：编译容器 + 每用例容器 ─────────────────────────

    @Test
    @DisplayName("编译 1 个容器 + 每用例 1 个容器；每条 docker run 都带全部隔离参数（含 --log-driver none）")
    void everyContainer_hasEveryIsolationFlag() {
        runner.respond(ok(""));          // compile
        runner.respond(ok("3\n"));       // case 0
        runner.respond(ok("3\n"));       // case 1
        exec(List.of("1 2\n", "2 1\n"));

        assertThat(runs()).hasSize(3);
        for (List<String> cmd : runs()) {
            assertThat(cmd.subList(0, 2)).containsExactly("docker", "run");
            assertFlag(cmd, "--rm");
            assertFlag(cmd, "--read-only");
            assertPair(cmd, "--log-driver", "none");
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
            assertPair(cmd, "--ulimit", "nofile=1024:1024");
            assertPair(cmd, "--workdir", "/tmp");
            assertPair(cmd, "--env", "HOME=/tmp");
            assertPair(cmd, "--entrypoint", "timeout");
            assertThat(cmd.get(cmd.indexOf("--name") + 1)).matches("wj-code-[0-9a-f]{32}");
            assertThat(cmd).noneMatch(a -> a.startsWith("--privileged") || a.startsWith("--cap-add")
                    || a.equals("host") || a.startsWith("--device") || a.contains("docker.sock")
                    || a.startsWith("--volume") || a.equals("-v") || a.equals("-t") || a.equals("-i")
                    || a.equals("--interactive") || a.equals("--tty"));
        }
        // 每个容器名字都不同
        assertThat(runs().stream().map(c -> c.get(c.indexOf("--name") + 1)).distinct()).hasSize(3);
    }

    @Test
    @DisplayName("编译容器：源码只读 + class 目录可写（唯一可写挂载）+ 脚本只读；不挂任何用例输入；PID 1 = timeout 编译上限")
    void compileCommand_exactShape() {
        runner.respond(ok(""));
        runner.respond(ok("3\n"));
        exec(List.of("1 2\n"));

        List<String> cmd = runs().get(0);
        List<String> mounts = mounts(cmd);
        assertThat(mounts).hasSize(3);
        assertThat(mounts.get(0)).matches("type=bind,source=.*[\\\\/]src,target=/src,readonly");
        assertThat(mounts.get(1)).matches("type=bind,source=.*[\\\\/]classes,target=/classes");
        assertThat(mounts.get(2)).matches("type=bind,source=.*[\\\\/]wj,target=/wj,readonly");
        assertThat(mounts.stream().filter(m -> !m.endsWith(",readonly"))).hasSize(1);
        assertThat(String.join(" ", cmd)).doesNotContain("target=/in");
        assertThat(cmd.subList(cmd.indexOf("--entrypoint"), cmd.size())).containsExactly(
                "--entrypoint", "timeout", "eclipse-temurin:21-jdk", "-s", "KILL", "15", "/bin/sh",
                "/wj/compile.sh", "128");
        assertThat(runner.timeouts.get(0)).isEqualTo(Duration.ofSeconds(15 + DockerCodeSandbox.CONTAINER_SLACK_SECONDS));
    }

    @Test
    @DisplayName("用例容器：class 只读 + 只有本用例的输入目录只读 + 脚本只读；没有可写挂载；PID 1 = timeout 运行上限；宿主读上限 = LIM")
    void caseCommand_mountsOnlyItsOwnInput() {
        runner.respond(ok(""));
        runner.respond(ok("a"));
        runner.respond(ok("b"));
        runner.respond(ok("c"));
        exec(List.of("x", "y", "z"));

        List<List<String>> cases = runs().subList(1, 4);
        for (int i = 0; i < 3; i++) {
            List<String> cmd = cases.get(i);
            List<String> mounts = mounts(cmd);
            assertThat(mounts).hasSize(3).allMatch(m -> m.endsWith(",readonly"));
            assertThat(mounts.get(0)).matches("type=bind,source=.*[\\\\/]classes,target=/classes,readonly");
            assertThat(mounts.get(1)).matches("type=bind,source=.*[\\\\/]in[\\\\/]" + i + ",target=/in,readonly");
            assertThat(mounts.get(2)).matches("type=bind,source=.*[\\\\/]wj,target=/wj,readonly");
            // 其它用例（含 hidden）的输入目录绝不出现在这个容器的命令行里
            for (int j = 0; j < 3; j++) {
                if (j != i) {
                    int other = j;
                    assertThat(cmd).noneMatch(a -> a.matches(".*[\\\\/]in[\\\\/]" + other + ",.*"));
                }
            }
            assertThat(String.join(" ", cmd)).doesNotContain("target=/src");
            assertThat(cmd.subList(cmd.indexOf("--entrypoint"), cmd.size())).containsExactly(
                    "--entrypoint", "timeout", "eclipse-temurin:21-jdk", "-s", "KILL", "3", "/bin/sh",
                    "/wj/case.sh", "65537", "128");
            assertThat(runner.timeouts.get(i + 1)).isEqualTo(Duration.ofSeconds(3 + DockerCodeSandbox.CONTAINER_SLACK_SECONDS));
            assertThat(runner.stdoutCaps.get(i + 1)).isEqualTo(65_537);
            assertThat(runner.stderrCaps.get(i + 1)).isEqualTo(65_537);
        }
    }

    @Test
    @DisplayName("限额改动反映到两类命令行")
    void commands_followLimits() {
        cfg.setBinary("/usr/bin/docker");
        cfg.setImage("my/jdk:21");
        runner.respond(ok(""));
        runner.respond(ok(""));
        sandbox.execute(new SandboxRequest(SOURCE, List.of("a"), new SandboxLimits(10, 2, 512, 200, 0.5, 32, 1000)));
        for (List<String> cmd : runs()) {
            assertThat(cmd.get(0)).isEqualTo("/usr/bin/docker");
            assertPair(cmd, "--cpus", "0.5");
            assertPair(cmd, "--memory", "512m");
            assertPair(cmd, "--memory-swap", "512m");
            assertPair(cmd, "--pids-limit", "32");
            assertThat(cmd).contains("my/jdk:21");
        }
        assertThat(runs().get(0).subList(runs().get(0).size() - 5, runs().get(0).size()))
                .containsExactly("KILL", "10", "/bin/sh", "/wj/compile.sh", "200");
        assertThat(runs().get(1).subList(runs().get(1).size() - 6, runs().get(1).size()))
                .containsExactly("KILL", "2", "/bin/sh", "/wj/case.sh", "1001", "200");
    }

    // ───────────────────────── 宿主目录 ─────────────────────────

    @Test
    @DisplayName("宿主目录：私有父目录下的 run-*：src/Main.java、wj/两段脚本、in/<i>/stdin.txt（UTF-8、LF）；期望输出不落盘；执行后删除")
    void runDir_writtenThenDeleted() {
        List<Path> seen = new ArrayList<>();
        runner.onRun = cmd -> {
            for (String m : mounts(cmd)) {
                seen.add(Path.of(m.substring("type=bind,source=".length(), m.indexOf(",target="))));
            }
            if (seen.size() == 3) { // 编译容器
                Path root = seen.get(0).getParent();
                try {
                    assertThat(Files.readString(root.resolve("src/Main.java"))).isEqualTo(SOURCE);
                    assertThat(Files.readString(root.resolve("in/0/stdin.txt"))).isEqualTo("1 2\n");
                    assertThat(Files.readString(root.resolve("in/1/stdin.txt"))).isEqualTo("中文");
                    assertThat(Files.readString(root.resolve("wj/compile.sh"))).isEqualTo(DockerRunnerScript.compileScript());
                    assertThat(Files.readString(root.resolve("wj/case.sh"))).isEqualTo(DockerRunnerScript.caseScript());
                    assertThat(Files.isDirectory(root.resolve("classes"))).isTrue();
                    try (var files = Files.walk(root)) {
                        assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(5);
                    }
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
        };
        runner.respond(ok(""));
        runner.respond(ok("3"));
        runner.respond(ok("ok"));

        SandboxResult r = exec(List.of("1 2\n", "中文"));

        assertThat(r.status()).isEqualTo(SandboxResult.Status.COMPLETED);
        assertThat(r.cases()).hasSize(2);
        Path root = seen.get(0).getParent();
        assertThat(root.getFileName().toString()).startsWith("run-");
        assertThat(root.getParent()).isEqualTo(tmp.resolve(DockerCodeSandbox.PRIVATE_DIR));
        assertThat(root).doesNotExist();
    }

    @Test
    @DisplayName("POSIX：私有父目录 0700、run 目录 0700、src/wj/in/<i> 0755、classes 0777、文件 0644")
    void runDir_posixPermissions() throws IOException {
        Assumptions.assumeTrue(Files.getFileAttributeView(tmp, PosixFileAttributeView.class) != null, "POSIX only");
        List<String> problems = new ArrayList<>();
        runner.onRun = cmd -> {
            if (!mounts(cmd).get(0).contains("target=/src")) {
                return;
            }
            String srcMount = mounts(cmd).get(0);
            Path root = Path.of(srcMount.substring("type=bind,source=".length(), srcMount.indexOf(",target="))).getParent();
            try {
                expectPerm(problems, root.getParent(), "rwx------");
                expectPerm(problems, root, "rwx------");
                expectPerm(problems, root.resolve("src"), "rwxr-xr-x");
                expectPerm(problems, root.resolve("src/Main.java"), "rw-r--r--");
                expectPerm(problems, root.resolve("classes"), "rwxrwxrwx");
                expectPerm(problems, root.resolve("wj"), "rwxr-xr-x");
                expectPerm(problems, root.resolve("wj/case.sh"), "rw-r--r--");
                expectPerm(problems, root.resolve("in"), "rwx------");
                expectPerm(problems, root.resolve("in/0"), "rwxr-xr-x");
                expectPerm(problems, root.resolve("in/0/stdin.txt"), "rw-r--r--");
            } catch (IOException e) {
                problems.add(e.toString());
            }
        };
        runner.respond(ok(""));
        runner.respond(ok("3"));
        exec(List.of("1 2\n"));
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("POSIX：私有父目录被放宽过 → 收紧回 0700；是符号链接 → FAILED，不调用 docker")
    void privateParent_hardened() throws IOException {
        Assumptions.assumeTrue(Files.getFileAttributeView(tmp, PosixFileAttributeView.class) != null, "POSIX only");
        Path parent = Files.createDirectory(tmp.resolve(DockerCodeSandbox.PRIVATE_DIR),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx")));
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxrwxrwx"));
        runner.respond(ok(""));
        runner.respond(ok("3"));
        exec(List.of(""));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(parent))).isEqualTo("rwx------");

        Path other = Files.createDirectories(tmp.resolve("elsewhere"));
        Path base2 = Files.createDirectories(tmp.resolve("base2"));
        Files.createSymbolicLink(base2.resolve(DockerCodeSandbox.PRIVATE_DIR), other);
        cfg.setWorkDir(base2.toString());
        runner.calls.clear();
        assertThat(exec(List.of("")).status()).isEqualTo(SandboxResult.Status.FAILED);
        assertThat(runs()).isEmpty();
        try (var s = Files.list(other)) {
            assertThat(s.count()).isZero();
        }
    }

    @Test
    @DisplayName("工作目录含逗号（会破坏 --mount 解析）→ FAILED，不调用 docker")
    void commaInWorkDir_rejected() throws IOException {
        Path bad = Files.createDirectories(tmp.resolve("a,b"));
        cfg.setWorkDir(bad.toString());
        assertThat(exec(List.of("")).status()).isEqualTo(SandboxResult.Status.FAILED);
        assertThat(runner.calls).isEmpty();
    }

    // ───────────────────────── 结果映射（全部基于宿主测量） ─────────────────────────

    @Test
    @DisplayName("编译失败 / 编译超时（容器内 timeout 杀掉 且 宿主测得 ≥ 上限）映射；编译失败不跑任何用例")
    void compileOutcomes() {
        runner.respond(result(1, "/src/Main.java:1: error: x", "", false, 900));
        SandboxResult ce = exec(List.of("", ""));
        assertThat(ce.status()).isEqualTo(SandboxResult.Status.COMPILE_ERROR);
        assertThat(ce.compileOutput()).contains("error: x");
        assertThat(runs()).hasSize(1);

        runner.respond(result(124, "", "", false, 15_200));
        assertThat(exec(List.of("")).status()).isEqualTo(SandboxResult.Status.COMPILE_TIMEOUT);
        runner.respond(result(137, "", "", false, 15_050));
        assertThat(exec(List.of("")).status()).isEqualTo(SandboxResult.Status.COMPILE_TIMEOUT);
        // 被杀但远未到上限（javac 被 OOM 杀）→ 编译失败而不是超时
        runner.respond(result(137, "", "", false, 2_000));
        assertThat(exec(List.of("")).status()).isEqualTo(SandboxResult.Status.COMPILE_ERROR);
    }

    @Test
    @DisplayName("用例：退出码、stdout/stderr、耗时 = 宿主测量；超过 LIM 的输出标记超限")
    void caseOutcomes() {
        runner.respond(ok(""));
        runner.respond(result(0, "3\n", "", false, 812));
        runner.respond(result(1, "", "Exception in thread \"main\"", false, 640));
        runner.respond(new ProcessRunner.Result(0, "x".repeat(65_537).getBytes(StandardCharsets.UTF_8), true, "",
                false, false, 700));
        SandboxResult r = exec(List.of("", "", ""));
        assertThat(r.status()).isEqualTo(SandboxResult.Status.COMPLETED);
        assertThat(r.cases()).hasSize(3);
        SandboxResult.CaseRun c0 = r.cases().get(0);
        assertThat(c0.exitCode()).isZero();
        assertThat(c0.stdout()).isEqualTo("3\n");
        assertThat(c0.durationMs()).isEqualTo(812);
        assertThat(c0.timedOut()).isFalse();
        assertThat(r.cases().get(1).exitCode()).isEqualTo(1);
        assertThat(r.cases().get(1).stderr()).contains("Exception");
        SandboxResult.CaseRun flood = r.cases().get(2);
        assertThat(flood.stdoutExceeded()).isTrue();
        assertThat(flood.stdout()).hasSize(65_536);
    }

    @Test
    @DisplayName("超时只认宿主测量：容器内被杀且宿主 ≥ 3s → 超时；学生伪造的早到 124 → 普通非零退出（运行错误）")
    void timeout_isHostMeasured() {
        runner.respond(ok(""));
        runner.respond(result(124, "", "", false, 400));   // 学生给 PID 1 发 SIGALRM 之类：不是超时
        runner.respond(result(0, "ok", "", false, 900));
        runner.respond(result(124, "", "", false, 3_050)); // 真超时 → fail-fast
        SandboxResult r = exec(List.of("", "", "", ""));
        assertThat(r.cases()).hasSize(3);
        assertThat(r.cases().get(0).timedOut()).isFalse();
        assertThat(DefaultCodeExecutionService.caseVerdict(r.cases().get(0)))
                .isEqualTo(CodeExecutionService.Verdict.RUNTIME_ERROR);
        assertThat(r.cases().get(2).timedOut()).isTrue();
        assertThat(runs()).hasSize(4); // 第 4 个用例没有起容器
    }

    @Test
    @DisplayName("宿主墙钟到期：docker rm -f 该容器，用例判超时，剩余用例不再运行")
    void hostTimeout_killsContainerAndStops() {
        runner.respond(ok(""));
        runner.respond(ok("3"));
        runner.respondTimeout(13_000);
        SandboxResult r = exec(List.of("", "", "", ""));
        assertThat(r.status()).isEqualTo(SandboxResult.Status.COMPLETED);
        assertThat(r.cases()).hasSize(2);
        assertThat(r.cases().get(1).timedOut()).isTrue();
        assertThat(r.cases().get(1).durationMs()).isEqualTo(13_000);
        List<String> timedOutRun = runs().get(2);
        String name = timedOutRun.get(timedOutRun.indexOf("--name") + 1);
        assertThat(runner.calls.get(runner.calls.size() - 1)).containsExactly("docker", "rm", "-f", name);
        assertThat(runs()).hasSize(3);
    }

    @Test
    @DisplayName("fail-fast：输出超限 / 被内核杀（137，未到时限）后不再运行剩余用例")
    void failFast_onResourceLimit() {
        runner.respond(ok(""));
        runner.respond(new ProcessRunner.Result(0, new byte[10], true, "", false, false, 3_100));
        assertThat(exec(List.of("", "", "")).cases()).hasSize(1);
        assertThat(runs()).hasSize(2);

        runner.calls.clear();
        runner.respond(ok(""));
        runner.respond(result(137, "", "", false, 800));
        SandboxResult oom = exec(List.of("", "", ""));
        assertThat(oom.cases()).hasSize(1);
        assertThat(DefaultCodeExecutionService.caseVerdict(oom.cases().get(0)))
                .isEqualTo(CodeExecutionService.Verdict.RESOURCE_LIMIT);
        assertThat(runs()).hasSize(2);
    }

    @Test
    @DisplayName("整次执行预算耗尽：不再起新容器，返回 TIMEOUT（已完成用例保留）")
    void totalBudget_exhausted() {
        runner.respond(ok(""));
        runner.respond(ok("1"));
        runner.afterRun = () -> nanos.addAndGet(Duration.ofSeconds(25).toNanos());
        // 预算 = 15 + 3×(3+1) + 20 = 47s；每个容器「花」25s → 第 2 个用例前耗尽
        SandboxResult r = exec(List.of("", "", ""));
        assertThat(r.status()).isEqualTo(SandboxResult.Status.TIMEOUT);
        assertThat(r.cases()).hasSize(1);
        assertThat(runs()).hasSize(2);
    }

    @Test
    @DisplayName("基础设施失败：docker 125/126/127、脚本自检失败、编译容器宿主超时、docker 不存在 → FAILED")
    void infrastructureFailures() {
        runner.respond(result(125, "", "docker: Error response from daemon: No such image", false, 50));
        assertThat(exec(List.of("")).status()).isEqualTo(SandboxResult.Status.FAILED);

        runner.respond(ok(""));
        runner.respond(result(127, "", "exec: \"timeout\": executable file not found", false, 50));
        SandboxResult caseInfra = exec(List.of(""));
        assertThat(caseInfra.status()).isEqualTo(SandboxResult.Status.FAILED);
        assertThat(caseInfra.detail()).contains("exit=127");

        runner.respondTimeout(25_000);
        assertThat(exec(List.of("")).status()).isEqualTo(SandboxResult.Status.FAILED);

        runner.fail = new IOException("docker: not found");
        assertThat(exec(List.of("")).status()).isEqualTo(SandboxResult.Status.FAILED);
    }

    @Test
    @DisplayName("中断：销毁当前容器并恢复中断标记")
    void interrupted_killsAndRestoresFlag() {
        runner.interrupt = true;
        SandboxResult r = exec(List.of(""));
        assertThat(r.status()).isEqualTo(SandboxResult.Status.FAILED);
        assertThat(Thread.interrupted()).isTrue();
        assertThat(runner.calls.get(1).subList(0, 3)).containsExactly("docker", "rm", "-f");
    }

    // ───────────────────────── available ─────────────────────────

    @Test
    @DisplayName("available（阻塞探测，由后台刷新线程调用）：docker version 与 image inspect 都成功才可用；每次调用都真查")
    void available_probes() {
        runner.respond(0, "27.3.1\n".getBytes(StandardCharsets.UTF_8));
        runner.respond(0, "sha256:abc\n".getBytes(StandardCharsets.UTF_8));
        assertThat(sandbox.available()).isTrue();
        assertThat(runner.calls.get(0)).containsExactly("docker", "version", "--format", "{{.Server.Version}}");
        assertThat(runner.calls.get(1)).containsExactly("docker", "image", "inspect", "--format", "{{.Id}}",
                "eclipse-temurin:21-jdk");

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

        runner.fail = new IOException("CreateProcess error=2");
        assertThat(sandbox.available()).isFalse();

        runner.fail = null;
        runner.respondTimeout(10_000);
        assertThat(sandbox.available()).isFalse();
    }

    // ───────────────────────── helpers ─────────────────────────

    private static ProcessRunner.Result ok(String stdout) {
        return result(0, stdout, "", false, 500);
    }

    private static ProcessRunner.Result result(int exit, String stdout, String stderr, boolean timedOut, long ms) {
        return new ProcessRunner.Result(exit, stdout.getBytes(StandardCharsets.UTF_8), false, stderr, timedOut,
                false, ms);
    }

    private static List<String> mounts(List<String> cmd) {
        List<String> m = new ArrayList<>();
        for (int i = 0; i < cmd.size() - 1; i++) {
            if (cmd.get(i).equals("--mount")) {
                m.add(cmd.get(i + 1));
            }
        }
        return m;
    }

    private static void expectPerm(List<String> problems, Path p, String perm) throws IOException {
        String actual = PosixFilePermissions.toString(Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS));
        if (!actual.equals(perm)) {
            problems.add(p + " expected " + perm + " but was " + actual);
        }
    }

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
        final List<Integer> stdoutCaps = new ArrayList<>();
        final List<Integer> stderrCaps = new ArrayList<>();
        final List<Result> queue = new ArrayList<>();
        Consumer<List<String>> onRun;
        Runnable afterRun;
        IOException fail;
        boolean interrupt;

        void respond(Result r) {
            queue.add(r);
        }

        void respond(int exit, byte[] stdout) {
            queue.add(new Result(exit, stdout, false, "", false));
        }

        void respondTimeout(long elapsedMs) {
            queue.add(new Result(-1, new byte[0], false, "", true, false, elapsedMs));
        }

        @Override
        public Result run(List<String> command, Duration timeout, int maxStdoutBytes, int maxStderrBytes)
                throws IOException, InterruptedException {
            calls.add(List.copyOf(command));
            timeouts.add(timeout);
            stdoutCaps.add(maxStdoutBytes);
            stderrCaps.add(maxStderrBytes);
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
            if (isRun && afterRun != null) {
                afterRun.run();
            }
            if (queue.isEmpty()) {
                return new Result(0, new byte[0], false, "", false);
            }
            return queue.remove(0);
        }
    }
}
