package com.wenjin.service.code;

import com.wenjin.config.CodeCheckProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 本机 docker CLI 沙箱：每次执行起一个一次性容器，在容器内编译并逐用例运行（见 {@link DockerRunnerScript}）。
 *
 * <h2>隔离（全部体现在 {@link #runCommand} 的命令行上，单测逐项断言）</h2>
 * 无网络、CPU/内存（禁 swap）/进程数上限、只读根文件系统 + 64MB noexec tmpfs、非 root（65534）、
 * 去全部 capabilities、no-new-privileges、禁 core dump、{@code --rm} 用完即毁、{@code --pull never}
 * （执行路径绝不拉镜像）、PID 1 为 {@code timeout -s KILL}（容器自限寿命）。源码与用例输入放在宿主临时目录，<b>只读</b>绑定挂载到 /work；期望输出不进容器。
 * 宿主再加整体墙钟上限，超时 {@code docker rm -f} 强制销毁。
 *
 * <h2>宿主</h2>
 * 生产目标是 Linux；Windows 宿主（Docker Desktop）使用 {@code --mount type=bind,source=D:\...} 同样可用
 * （{@code -v} 的冒号语法会与盘符冲突，故统一用 {@code --mount}）。
 */
public class DockerCodeSandbox implements CodeSandbox {

    private static final Logger log = LoggerFactory.getLogger(DockerCodeSandbox.class);

    /** 容器创建/启动/销毁的墙钟余量（秒） */
    static final int STARTUP_SLACK_SECONDS = 20;
    /** 容器内自限（PID 1 = timeout）比宿主墙钟提前这么多秒到期 */
    static final int IN_CONTAINER_MARGIN_SECONDS = 10;
    static final String CONTAINER_USER = "65534:65534";
    static final String TMPFS = "/tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration KILL_TIMEOUT = Duration.ofSeconds(20);
    private static final int LOG_SNIPPET = 300;

    private final CodeCheckProperties.Docker cfg;
    private final ProcessRunner runner;
    private final Clock clock;

    private volatile CachedAvailability cached;

    private record CachedAvailability(boolean value, Instant expiresAt) {
    }

    public DockerCodeSandbox(CodeCheckProperties.Docker cfg, ProcessRunner runner, Clock clock) {
        this.cfg = cfg;
        this.runner = runner;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "docker";
    }

    // ───────────────────────── available ─────────────────────────

    @Override
    public boolean available() {
        Instant now = clock.instant();
        CachedAvailability c = cached;
        if (c != null && now.isBefore(c.expiresAt())) {
            return c.value();
        }
        boolean value = probe();
        cached = new CachedAvailability(value,
                now.plusSeconds(Math.max(1, cfg.getAvailabilityCacheSeconds())));
        return value;
    }

    private boolean probe() {
        try {
            ProcessRunner.Result version = runner.run(
                    List.of(cfg.getBinary(), "version", "--format", "{{.Server.Version}}"), PROBE_TIMEOUT, 256);
            if (version.timedOut() || version.exitCode() != 0) {
                log.warn("代码沙箱不可用：docker 守护进程无响应（exit={} timeout={}）",
                        version.exitCode(), version.timedOut());
                return false;
            }
            ProcessRunner.Result image = runner.run(
                    List.of(cfg.getBinary(), "image", "inspect", "--format", "{{.Id}}", cfg.getImage()),
                    PROBE_TIMEOUT, 512);
            if (image.timedOut() || image.exitCode() != 0) {
                log.warn("代码沙箱不可用：镜像 {} 不在本机（需预先 docker pull）", cfg.getImage());
                return false;
            }
            return true;
        } catch (IOException e) {
            log.warn("代码沙箱不可用：无法调用 docker CLI（{}）", e.getClass().getSimpleName());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ───────────────────────── execute ─────────────────────────

    @Override
    public SandboxResult execute(SandboxRequest request) {
        SandboxLimits limits = request.limits();
        int n = request.stdins().size();
        Path dir = null;
        String container = "wj-code-" + UUID.randomUUID().toString().replace("-", "");
        try {
            dir = prepareWorkDir(request);
            List<String> cmd = runCommand(cfg, limits, container, dir, n);
            Duration wall = Duration.ofSeconds(budgetSeconds(limits, n) + STARTUP_SLACK_SECONDS);
            ProcessRunner.Result pr;
            try {
                pr = runner.run(cmd, wall, DockerRunnerScript.hostStdoutCap(limits, n));
            } catch (InterruptedException e) {
                forceRemove(container);
                Thread.currentThread().interrupt();
                return SandboxResult.failed("interrupted");
            }
            DockerRunnerScript.Parsed parsed = DockerRunnerScript.parse(pr.stdout(), limits.outputMaxBytes());
            if (pr.timedOut()) {
                forceRemove(container);
                log.info("代码沙箱整体超时（{}s），容器已强制销毁 name={}", wall.toSeconds(), container);
                return new SandboxResult(SandboxResult.Status.TIMEOUT, parsed.compileOutput(), parsed.cases(),
                        "host wall-clock timeout");
            }
            if (!parsed.begun()) {
                String detail = "docker run exit=" + pr.exitCode() + " stderr=" + snippet(pr.stderr());
                log.warn("代码沙箱执行失败（容器未启动）：{}", detail);
                return SandboxResult.failed(detail);
            }
            if (parsed.fatal() != null) {
                log.warn("代码沙箱运行环境不满足：{}（镜像 {}）", parsed.fatal(), cfg.getImage());
                return SandboxResult.failed("runner fatal: " + parsed.fatal());
            }
            if (parsed.compileExitCode() == null) {
                String detail = "runner aborted before compile, exit=" + pr.exitCode() + " stderr=" + snippet(pr.stderr());
                log.warn("代码沙箱执行失败：{}", detail);
                return SandboxResult.failed(detail);
            }
            int rc = parsed.compileExitCode();
            if (rc == 124) {
                return new SandboxResult(SandboxResult.Status.COMPILE_TIMEOUT, parsed.compileOutput(), List.of(), null);
            }
            if (rc != 0) {
                return new SandboxResult(SandboxResult.Status.COMPILE_ERROR, parsed.compileOutput(), List.of(), null);
            }
            if (!parsed.ended() && (pr.exitCode() == 124 || pr.exitCode() == 137)) {
                // 容器内 PID 1（timeout）到期自行结束：按整体超时处理
                log.info("代码沙箱容器内寿命到期 name={} exit={}", container, pr.exitCode());
                return new SandboxResult(SandboxResult.Status.TIMEOUT, parsed.compileOutput(), parsed.cases(),
                        "in-container lifetime exceeded");
            }
            if (!parsed.ended() || pr.stdoutTruncated()) {
                log.info("代码沙箱 runner 输出不完整（ended={} truncated={} cases={}/{}）",
                        parsed.ended(), pr.stdoutTruncated(), parsed.cases().size(), n);
            }
            return new SandboxResult(SandboxResult.Status.COMPLETED, parsed.compileOutput(), parsed.cases(), null);
        } catch (IOException | RuntimeException e) {
            log.warn("代码沙箱执行失败：{}", e.getClass().getSimpleName());
            return SandboxResult.failed(e.getClass().getSimpleName());
        } finally {
            deleteQuietly(dir);
        }
    }

    /** 编译 + 每用例（运行 + 1s 开销）的预算秒数。 */
    static long budgetSeconds(SandboxLimits l, int caseCount) {
        return l.compileTimeoutSeconds() + (long) caseCount * (l.runTimeoutSeconds() + 1);
    }

    /**
     * {@code docker run} 完整命令行（安全参数的唯一出处）。容器 PID 1 是 {@code timeout -s KILL}，
     * 即使宿主进程崩溃、来不及 {@code docker rm -f}，容器也会在预算 + 余量后自行结束（再由 --rm 删除）。
     */
    static List<String> runCommand(CodeCheckProperties.Docker cfg, SandboxLimits l, String container, Path workDir,
                                   int caseCount) {
        String source = workDir.toAbsolutePath().toString();
        if (source.contains(",")) {
            throw new IllegalArgumentException("work dir must not contain ','");
        }
        List<String> c = new ArrayList<>();
        c.add(cfg.getBinary());
        c.add("run");
        c.add("--rm");
        c.add("--name");
        c.add(container);
        c.add("--pull");
        c.add("never");
        c.add("--network");
        c.add("none");
        c.add("--cpus");
        c.add(BigDecimal.valueOf(l.cpus()).toPlainString());
        c.add("--memory");
        c.add(l.memoryMb() + "m");
        c.add("--memory-swap");
        c.add(l.memoryMb() + "m");
        c.add("--pids-limit");
        c.add(String.valueOf(l.pids()));
        c.add("--read-only");
        c.add("--tmpfs");
        c.add(TMPFS);
        c.add("--cap-drop");
        c.add("ALL");
        c.add("--security-opt");
        c.add("no-new-privileges");
        c.add("--user");
        c.add(CONTAINER_USER);
        c.add("--ulimit");
        c.add("core=0:0");
        c.add("--ulimit");
        c.add("nofile=1024:1024");
        c.add("--workdir");
        c.add("/tmp");
        c.add("--env");
        c.add("HOME=/tmp");
        c.add("--mount");
        c.add("type=bind,source=" + source + ",target=/work,readonly");
        c.add("--entrypoint");
        c.add("timeout");
        c.add(cfg.getImage());
        c.add("-s");
        c.add("KILL");
        c.add(String.valueOf(budgetSeconds(l, caseCount) + IN_CONTAINER_MARGIN_SECONDS));
        c.add("/bin/sh");
        c.add("/work/run.sh");
        return c;
    }

    private Path prepareWorkDir(SandboxRequest request) throws IOException {
        Path base = cfg.getWorkDir() == null || cfg.getWorkDir().isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"))
                : Path.of(cfg.getWorkDir());
        Files.createDirectories(base);
        Path dir = Files.createTempDirectory(base, "wj-code-");
        Path in = Files.createDirectory(dir.resolve("in"));
        Files.writeString(dir.resolve("Main.java"), request.source(), StandardCharsets.UTF_8);
        List<String> stdins = request.stdins();
        for (int i = 0; i < stdins.size(); i++) {
            String s = stdins.get(i) == null ? "" : stdins.get(i);
            Files.writeString(in.resolve(i + ".txt"), s, StandardCharsets.UTF_8);
        }
        Files.writeString(dir.resolve("run.sh"), DockerRunnerScript.render(request.limits(), stdins.size()),
                StandardCharsets.UTF_8);
        makeReadableForContainer(dir);
        return dir;
    }

    /**
     * 容器内以 65534 运行，需要能读挂载目录。POSIX 宿主上 createTempDirectory 是 0700，这里放宽为
     * 目录 0755 / 文件 0644（只读；内容为学生自己的源码与公开用例输入）。Windows 宿主由 Docker Desktop 处理。
     */
    private static void makeReadableForContainer(Path dir) throws IOException {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Set<PosixFilePermission> dirPerm = PosixFilePermissions.fromString("rwxr-xr-x");
        Set<PosixFilePermission> filePerm = PosixFilePermissions.fromString("rw-r--r--");
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.toList()) {
                Files.setPosixFilePermissions(p, Files.isDirectory(p) ? dirPerm : filePerm);
            }
        }
    }

    private void forceRemove(String container) {
        boolean interrupted = Thread.interrupted();
        try {
            ProcessRunner.Result r = runner.run(List.of(cfg.getBinary(), "rm", "-f", container), KILL_TIMEOUT, 1024);
            if (r.timedOut() || r.exitCode() != 0) {
                log.warn("强制销毁沙箱容器失败 name={} exit={}", container, r.exitCode());
            }
        } catch (IOException e) {
            log.warn("强制销毁沙箱容器失败 name={} ({})", container, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("沙箱临时目录清理失败 dir={}", dir);
        }
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip().replaceAll("[\\r\\n]+", " ");
        return t.length() <= LOG_SNIPPET ? t : t.substring(0, LOG_SNIPPET) + "…";
    }
}
