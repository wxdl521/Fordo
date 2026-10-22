package com.wenjin.service.code;

import com.wenjin.config.CodeCheckProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * 本机 docker CLI 沙箱：<b>编译一次，每个用例一个一次性容器</b>（脚本见 {@link DockerRunnerScript}）。
 *
 * <h2>为什么每用例一个容器</h2>
 * 同一容器里 runner 与学生代码同为 uid 65534：学生能读到所有用例输入（含 hidden）、能 {@code setsid} 逃出每用例
 * 超时、能向 runner 发信号或经 {@code /proc/1/fd/1} 伪造结果行。拆成每用例一个容器后，一个容器里只挂载
 * <b>该用例自己的</b>输入；学生能篡改的只有它自己这个用例的输出与退出码，而判定（墙钟、超时、截断、比对）全在宿主侧。
 *
 * <h2>隔离（全部体现在 {@link #compileCommand} / {@link #caseCommand} 的命令行上，单测逐项断言）</h2>
 * 无网络、CPU/内存（禁 swap）/进程数上限、只读根文件系统 + 64MB noexec tmpfs、非 root（65534）、
 * 去全部 capabilities、no-new-privileges、禁 core dump、{@code --rm} 用完即毁、{@code --pull never}
 * （执行路径绝不拉镜像）、{@code --log-driver none}（学生输出不落 docker 日志；attach 的 stdout/stderr 不受影响）、
 * PID 1 为 {@code timeout -s KILL}（容器自限寿命）。宿主对每个容器再加墙钟上限，超时 {@code docker rm -f}。
 *
 * <h2>宿主临时目录</h2>
 * {@code <work-dir>/wj-code-sandbox/}（0700、属主必须是应用账户、不得是符号链接）下每次执行一个 0700 的
 * {@code run-*} 目录：{@code src/}（0755，Main.java 0644）、{@code classes/}（0777，编译容器唯一可写挂载）、
 * {@code wj/}（0755，两段脚本 0644）、{@code in/<i>/stdin.txt}（目录 0755 / 文件 0644）。容器经绑定挂载直接看到
 * 子目录本身，不需要穿越 0700 的父目录（挂载由 docker 守护进程完成）；宿主上其它本地用户则无法穿越私有父目录。
 * 期望输出从不写盘、从不进容器。
 *
 * <h2>宿主</h2>
 * 生产目标是 Linux；Windows 宿主（Docker Desktop）使用 {@code --mount type=bind,source=D:\...} 同样可用
 * （{@code -v} 的冒号语法会与盘符冲突，故统一用 {@code --mount}；Windows 上不设 POSIX 权限）。
 */
public class DockerCodeSandbox implements CodeSandbox {

    private static final Logger log = LoggerFactory.getLogger(DockerCodeSandbox.class);

    /** 整次执行（编译 + 全部用例）宿主墙钟预算之外的余量（秒） */
    static final int STARTUP_SLACK_SECONDS = 20;
    /** 单个容器：宿主墙钟 = 容器内寿命 + 这么多秒（容器创建/启动/销毁开销） */
    static final int CONTAINER_SLACK_SECONDS = 10;
    static final String CONTAINER_USER = "65534:65534";
    static final String TMPFS = "/tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777";
    static final String PRIVATE_DIR = "wj-code-sandbox";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration KILL_TIMEOUT = Duration.ofSeconds(20);
    private static final int COMPILE_STDERR_CAP = 4096;
    private static final int LOG_SNIPPET = 300;

    static final Set<PosixFilePermission> PERM_PRIVATE = PosixFilePermissions.fromString("rwx------");
    static final Set<PosixFilePermission> PERM_DIR_READ = PosixFilePermissions.fromString("rwxr-xr-x");
    static final Set<PosixFilePermission> PERM_DIR_WRITE = PosixFilePermissions.fromString("rwxrwxrwx");
    static final Set<PosixFilePermission> PERM_FILE_READ = PosixFilePermissions.fromString("rw-r--r--");

    private final CodeCheckProperties.Docker cfg;
    private final ProcessRunner runner;
    private final LongSupplier nanoTime;

    private volatile UserPrincipal appUser;

    /** 本次执行的宿主目录布局。 */
    record RunDirs(Path root, Path src, Path classes, Path scripts, Path inputs) {
        Path caseDir(int i) {
            return inputs.resolve(String.valueOf(i));
        }
    }

    public DockerCodeSandbox(CodeCheckProperties.Docker cfg, ProcessRunner runner) {
        this(cfg, runner, System::nanoTime);
    }

    DockerCodeSandbox(CodeCheckProperties.Docker cfg, ProcessRunner runner, LongSupplier nanoTime) {
        this.cfg = cfg;
        this.runner = runner;
        this.nanoTime = nanoTime;
    }

    @Override
    public String name() {
        return "docker";
    }

    // ───────────────────────── available ─────────────────────────

    /**
     * <b>阻塞</b>探测：{@code docker version} + {@code docker image inspect}（各 ≤10s）。生产中只由
     * {@link AsyncAvailabilitySandbox} 的后台线程调用，请求路径读的是它缓存的结果。
     */
    @Override
    public boolean available() {
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
        RunDirs dirs = null;
        long deadline = nanoTime.getAsLong() + Duration.ofSeconds(budgetSeconds(limits, n) + STARTUP_SLACK_SECONDS).toNanos();
        try {
            dirs = prepareRunDirs(request);

            // ── 1. 编译容器 ──
            String compileName = containerName();
            ProcessRunner.Result cr = runContainer(compileCommand(cfg, limits, compileName, dirs),
                    compileName, Duration.ofSeconds(limits.compileTimeoutSeconds() + CONTAINER_SLACK_SECONDS),
                    DockerRunnerScript.caseOutputCap(limits), COMPILE_STDERR_CAP);
            if (cr == null) {
                return SandboxResult.failed("interrupted");
            }
            if (cr.timedOut()) {
                log.warn("代码沙箱编译容器超出宿主墙钟（{}s），已强制销毁 name={}",
                        limits.compileTimeoutSeconds() + CONTAINER_SLACK_SECONDS, compileName);
                return SandboxResult.failed("compile container host timeout");
            }
            if (DockerRunnerScript.isInfrastructureExit(cr.exitCode())) {
                String detail = "compile container exit=" + cr.exitCode() + " stderr=" + snippet(cr.stderr());
                log.warn("代码沙箱执行失败：{}（镜像 {}）", detail, cfg.getImage());
                return SandboxResult.failed(detail);
            }
            String compileOut = utf8(cr.stdout(), limits.outputMaxBytes());
            if (cr.exitCode() != 0) {
                boolean killed = cr.exitCode() == 124 || cr.exitCode() == 137;
                if (killed && cr.elapsedMs() >= limits.compileTimeoutSeconds() * 1000L) {
                    return new SandboxResult(SandboxResult.Status.COMPILE_TIMEOUT, compileOut, List.of(), null);
                }
                return new SandboxResult(SandboxResult.Status.COMPILE_ERROR, compileOut, List.of(), null);
            }

            // ── 2. 每用例一个容器；首个 TIMEOUT / RESOURCE_LIMIT 后不再跑剩余用例 ──
            List<SandboxResult.CaseRun> cases = new ArrayList<>(n);
            int cap = DockerRunnerScript.caseOutputCap(limits);
            Duration caseWall = Duration.ofSeconds(limits.runTimeoutSeconds() + CONTAINER_SLACK_SECONDS);
            for (int i = 0; i < n; i++) {
                if (nanoTime.getAsLong() - deadline > 0) {
                    log.info("代码沙箱整次执行预算耗尽，剩余 {} 个用例未运行", n - i);
                    return new SandboxResult(SandboxResult.Status.TIMEOUT, compileOut, cases, "total budget exhausted");
                }
                String name = containerName();
                ProcessRunner.Result r = runContainer(caseCommand(cfg, limits, name, dirs, i), name, caseWall, cap, cap);
                if (r == null) {
                    return SandboxResult.failed("interrupted");
                }
                if (!r.timedOut() && DockerRunnerScript.isInfrastructureExit(r.exitCode())) {
                    String detail = "case container exit=" + r.exitCode() + " stderr=" + snippet(r.stderr());
                    log.warn("代码沙箱执行失败：{}", detail);
                    return SandboxResult.failed(detail);
                }
                SandboxResult.CaseRun run = toCaseRun(i, r, limits);
                cases.add(run);
                if (DefaultCodeExecutionService.stopsJudging(run)) {
                    break;
                }
            }
            return new SandboxResult(SandboxResult.Status.COMPLETED, compileOut, cases, null);
        } catch (IOException | RuntimeException e) {
            log.warn("代码沙箱执行失败：{}", e.getClass().getSimpleName());
            return SandboxResult.failed(e.getClass().getSimpleName());
        } finally {
            deleteQuietly(dirs == null ? null : dirs.root());
        }
    }

    /**
     * 宿主侧判定：墙钟与「是否超时」只看宿主测量。容器内 timeout 杀掉（124/137）且宿主测得 ≥ 每用例上限 → 超时；
     * 宿主自己的墙钟到期（已 docker rm -f）→ 超时。学生程序自己伪造的 124 只会被当成非零退出（运行错误）。
     */
    static SandboxResult.CaseRun toCaseRun(int index, ProcessRunner.Result r, SandboxLimits l) {
        int max = l.outputMaxBytes();
        boolean killed = r.exitCode() == 124 || r.exitCode() == 137;
        boolean timedOut = r.timedOut() || (killed && r.elapsedMs() >= l.runTimeoutSeconds() * 1000L);
        byte[] so = r.stdout();
        byte[] se = r.stderr().getBytes(StandardCharsets.UTF_8);
        return new SandboxResult.CaseRun(index, r.timedOut() ? 124 : r.exitCode(), timedOut, r.elapsedMs(),
                utf8(so, max), r.stdoutTruncated() || so.length > max,
                r.stderr().length() > max ? r.stderr().substring(0, max) : r.stderr(),
                r.stderrTruncated() || se.length > max);
    }

    /** 整次执行的宿主预算秒数：编译 + 每用例（运行 + 1s 容器开销）。 */
    static long budgetSeconds(SandboxLimits l, int caseCount) {
        return l.compileTimeoutSeconds() + (long) caseCount * (l.runTimeoutSeconds() + 1);
    }

    /** 运行一个容器；宿主墙钟到期时 docker rm -f。中断时销毁容器、恢复中断标记并返回 null。 */
    private ProcessRunner.Result runContainer(List<String> cmd, String name, Duration wall, int stdoutCap,
                                              int stderrCap) throws IOException {
        ProcessRunner.Result r;
        try {
            r = runner.run(cmd, wall, stdoutCap, stderrCap);
        } catch (InterruptedException e) {
            forceRemove(name);
            Thread.currentThread().interrupt();
            return null;
        }
        if (r.timedOut()) {
            forceRemove(name);
        }
        return r;
    }

    /**
     * 编译容器命令行：源码目录只读、class 输出目录可写（唯一可写挂载）、脚本目录只读；不挂载任何用例输入。
     */
    static List<String> compileCommand(CodeCheckProperties.Docker cfg, SandboxLimits l, String container, RunDirs d) {
        List<String> c = isolatedRun(cfg, l, container);
        mount(c, d.src(), "/src", true);
        mount(c, d.classes(), "/classes", false);
        mount(c, d.scripts(), "/wj", true);
        entry(c, cfg, l.compileTimeoutSeconds());
        c.add("/wj/" + DockerRunnerScript.COMPILE_SH);
        c.add(String.valueOf(l.jvmHeapMb()));
        return c;
    }

    /**
     * 第 {@code index} 个用例的容器命令行：class 目录只读、<b>只有该用例</b>的输入目录只读、脚本目录只读。
     */
    static List<String> caseCommand(CodeCheckProperties.Docker cfg, SandboxLimits l, String container, RunDirs d,
                                    int index) {
        List<String> c = isolatedRun(cfg, l, container);
        mount(c, d.classes(), "/classes", true);
        mount(c, d.caseDir(index), "/in", true);
        mount(c, d.scripts(), "/wj", true);
        entry(c, cfg, l.runTimeoutSeconds());
        c.add("/wj/" + DockerRunnerScript.CASE_SH);
        c.add(String.valueOf(DockerRunnerScript.caseOutputCap(l)));
        c.add(String.valueOf(l.jvmHeapMb()));
        return c;
    }

    /** 两类容器共用的 {@code docker run} 隔离参数（安全参数的唯一出处）。 */
    private static List<String> isolatedRun(CodeCheckProperties.Docker cfg, SandboxLimits l, String container) {
        List<String> c = new ArrayList<>();
        c.add(cfg.getBinary());
        c.add("run");
        c.add("--rm");
        c.add("--name");
        c.add(container);
        c.add("--pull");
        c.add("never");
        c.add("--log-driver");
        c.add("none");
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
        return c;
    }

    private static void mount(List<String> c, Path source, String target, boolean readonly) {
        String s = source.toAbsolutePath().toString();
        if (s.contains(",")) {
            throw new IllegalArgumentException("mount source must not contain ','");
        }
        c.add("--mount");
        c.add("type=bind,source=" + s + ",target=" + target + (readonly ? ",readonly" : ""));
    }

    /** PID 1 = {@code timeout -s KILL <seconds>}：即使宿主进程崩溃，容器也会到期自行结束（再由 --rm 删除）。 */
    private static void entry(List<String> c, CodeCheckProperties.Docker cfg, int seconds) {
        c.add("--entrypoint");
        c.add("timeout");
        c.add(cfg.getImage());
        c.add("-s");
        c.add("KILL");
        c.add(String.valueOf(seconds));
        c.add("/bin/sh");
    }

    private static String containerName() {
        return "wj-code-" + UUID.randomUUID().toString().replace("-", "");
    }

    // ───────────────────────── 宿主目录 ─────────────────────────

    private RunDirs prepareRunDirs(SandboxRequest request) throws IOException {
        Path base = cfg.getWorkDir() == null || cfg.getWorkDir().isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"))
                : Path.of(cfg.getWorkDir());
        if (base.toAbsolutePath().toString().contains(",")) {
            throw new IllegalArgumentException("work dir must not contain ','");
        }
        Path parent = privateParent(base);
        Path root = Files.createTempDirectory(parent, "run-");
        RunDirs d = new RunDirs(root, root.resolve("src"), root.resolve("classes"), root.resolve("wj"),
                root.resolve("in"));
        boolean posix = isPosix(root);
        if (posix) {
            Files.setPosixFilePermissions(root, PERM_PRIVATE);
        }
        dir(d.src(), posix ? PERM_DIR_READ : null);
        file(d.src().resolve("Main.java"), request.source(), posix);
        dir(d.classes(), posix ? PERM_DIR_WRITE : null);
        dir(d.scripts(), posix ? PERM_DIR_READ : null);
        file(d.scripts().resolve(DockerRunnerScript.COMPILE_SH), DockerRunnerScript.compileScript(), posix);
        file(d.scripts().resolve(DockerRunnerScript.CASE_SH), DockerRunnerScript.caseScript(), posix);
        dir(d.inputs(), posix ? PERM_PRIVATE : null);
        List<String> stdins = request.stdins();
        for (int i = 0; i < stdins.size(); i++) {
            dir(d.caseDir(i), posix ? PERM_DIR_READ : null);
            file(d.caseDir(i).resolve("stdin.txt"), stdins.get(i) == null ? "" : stdins.get(i), posix);
        }
        return d;
    }

    /**
     * {@code <base>/wj-code-sandbox}：不存在则以 0700 创建；已存在则必须是目录、不是符号链接、属主是应用账户，
     * 并收紧为 0700。不满足即抛出（本次执行 FAILED），绝不在别人能控制的目录里写学生代码。
     */
    Path privateParent(Path base) throws IOException {
        Files.createDirectories(base);
        Path p = base.resolve(PRIVATE_DIR);
        if (!isPosix(base)) {
            Files.createDirectories(p);
            return p;
        }
        try {
            Files.createDirectory(p, PosixFilePermissions.asFileAttribute(PERM_PRIVATE));
        } catch (FileAlreadyExistsException ignored) {
            // 校验下面做
        }
        PosixFileAttributes a = Files.readAttributes(p, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (a.isSymbolicLink() || !a.isDirectory()) {
            throw new IOException("sandbox parent is not a plain directory");
        }
        if (!a.owner().equals(appUser(base))) {
            throw new IOException("sandbox parent is owned by another user");
        }
        if (!a.permissions().equals(PERM_PRIVATE)) {
            Files.setPosixFilePermissions(p, PERM_PRIVATE);
        }
        return p;
    }

    /** 应用账户 = 我们自己新建的文件的属主（不依赖 user.name 能否反查）。 */
    private UserPrincipal appUser(Path base) throws IOException {
        UserPrincipal u = appUser;
        if (u == null) {
            Path probe = Files.createTempDirectory(base, ".wj-owner-");
            try {
                u = Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
            } finally {
                Files.deleteIfExists(probe);
            }
            appUser = u;
        }
        return u;
    }

    private static boolean isPosix(Path p) {
        return Files.getFileAttributeView(p, PosixFileAttributeView.class) != null;
    }

    private static void dir(Path p, Set<PosixFilePermission> perm) throws IOException {
        Files.createDirectory(p);
        if (perm != null) {
            Files.setPosixFilePermissions(p, perm); // 不受 umask 影响
        }
    }

    private static void file(Path p, String content, boolean posix) throws IOException {
        Files.writeString(p, content, StandardCharsets.UTF_8);
        if (posix) {
            Files.setPosixFilePermissions(p, PERM_FILE_READ);
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

    private static String utf8(byte[] b, int max) {
        int n = Math.min(b.length, Math.max(0, max));
        return new String(b, 0, n, StandardCharsets.UTF_8);
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip().replaceAll("[\\r\\n]+", " ");
        return t.length() <= LOG_SNIPPET ? t : t.substring(0, LOG_SNIPPET) + "…";
    }
}
