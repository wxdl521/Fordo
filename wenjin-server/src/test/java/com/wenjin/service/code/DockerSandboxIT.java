package com.wenjin.service.code;

import com.wenjin.config.CodeCheckProperties;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.service.code.CodeExecutionService.CaseResult;
import com.wenjin.service.code.CodeExecutionService.CodeJudgeResult;
import com.wenjin.service.code.CodeExecutionService.CodeRunResult;
import com.wenjin.service.code.CodeExecutionService.Verdict;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 真实 Docker 沙箱集成测试（failsafe，{@code mvn verify}）。
 *
 * <p>本机没有 Docker 时整类跳过；但在 CI（环境变量 {@code CI=true}，GitHub Actions 默认设置）里没有 Docker 视为失败，
 * 防止安全测试被静默跳过。镜像默认 {@code eclipse-temurin:21-jdk}（可用 {@code WENJIN_CODE_SANDBOX_IMAGE} 覆盖），
 * 不在本机时先 {@code docker pull}（CI 工作流里另有预拉取步骤）。</p>
 *
 * <p>覆盖：AC / WA / 编译错 / 死循环超时 / 刷屏输出超限 / 网络隔离 / 进程与线程炸弹被 pids 限住 /
 * 根文件系统与挂载目录只读 / 非 root + 无 capabilities + no-new-privileges / 大内存分配 / UTF-8 输出 /
 * 容器用完即删；以及「编译一次 + 每用例一个容器」的安全性质：用例容器里看不到别的（hidden）用例输入、
 * {@code setsid} 逃逸的子进程被每用例上限收掉、给 PID 1 发信号或伪造 docker 退出码都不能让用例通过或变成「未判分」、
 * 首个超时用例之后不再运行剩余用例、{@code --log-driver none} 下 attach 的 stdout/stderr 照常回传。</p>
 */
@Timeout(value = 15, unit = java.util.concurrent.TimeUnit.MINUTES)
class DockerSandboxIT {

    private static final String IMAGE = System.getenv().getOrDefault("WENJIN_CODE_SANDBOX_IMAGE",
            "eclipse-temurin:21-jdk");
    private static final long STUDENT = 42L;
    private static final long SUM_QUESTION = 1L;
    private static final long SECRET_QUESTION = 2L;
    private static final long LOOP_QUESTION = 3L;
    private static final String HIDDEN_SECRET = "HIDDEN_SECRET_7f3a";

    private static final Map<Long, List<CodeTestCase>> CASES = new HashMap<>();
    private static DefaultProcessRunner runner;
    private static LimitsProperties limits;
    private static DefaultCodeExecutionService service;

    @BeforeAll
    static void setUp() throws Exception {
        runner = new DefaultProcessRunner();
        if (!dockerUsable()) {
            if ("true".equalsIgnoreCase(System.getenv("CI"))) {
                fail("CI 环境必须提供 Docker：DockerSandboxIT 不允许被跳过");
            }
            Assumptions.assumeTrue(false, "本机没有可用的 Docker，跳过 DockerSandboxIT");
        }
        ensureImage();

        CodeCheckProperties props = new CodeCheckProperties();
        props.getDocker().setImage(IMAGE);
        limits = new LimitsProperties();
        limits.getCode().setPerStudentPerHour(0);
        DockerCodeSandbox sandbox = new DockerCodeSandbox(props.getDocker(), runner);
        assertThat(sandbox.available()).as("docker version + image inspect").isTrue();

        CASES.put(SUM_QUESTION, List.of(
                new CodeTestCase(1, "1 2\n", "3\n", false),
                new CodeTestCase(2, "-5 5\n", "0", false),
                new CodeTestCase(3, "1000000000 1000000000\n", "2000000000\n", true)));
        CASES.put(SECRET_QUESTION, List.of(
                new CodeTestCase(1, "VISIBLE_INPUT\n", "unused", false),
                new CodeTestCase(2, HIDDEN_SECRET + "\n", "unused", true)));
        CASES.put(LOOP_QUESTION, List.of(
                new CodeTestCase(1, "1\n", "1\n", false),
                new CodeTestCase(2, "2\n", "2\n", false),
                new CodeTestCase(3, "3\n", "3\n", true)));
        CodeTestCaseSource source = qid -> CASES.getOrDefault(qid, List.of());
        service = new DefaultCodeExecutionService(new FeatureFlagsProperties(), limits, sandbox, () -> source,
                Clock.systemUTC());
        assertThat(service.available()).isTrue();
    }

    @AfterAll
    static void noContainerLeftBehind() throws Exception {
        if (runner == null || service == null) {
            return;
        }
        String left = "";
        for (int i = 0; i < 20; i++) {
            ProcessRunner.Result r = runner.run(List.of("docker", "ps", "-a", "--filter", "name=wj-code-",
                    "--format", "{{.Names}}"), Duration.ofSeconds(20), 64 * 1024);
            left = new String(r.stdout(), StandardCharsets.UTF_8).strip();
            if (left.isEmpty()) {
                break;
            }
            Thread.sleep(500);
        }
        assertThat(left).as("--rm 之后不应残留沙箱容器").isEmpty();
    }

    private static boolean dockerUsable() {
        try {
            ProcessRunner.Result r = runner.run(List.of("docker", "version", "--format", "{{.Server.Version}}"),
                    Duration.ofSeconds(20), 1024);
            return !r.timedOut() && r.exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void ensureImage() throws Exception {
        ProcessRunner.Result inspect = runner.run(List.of("docker", "image", "inspect", "--format", "{{.Id}}", IMAGE),
                Duration.ofSeconds(30), 1024);
        if (inspect.exitCode() == 0) {
            return;
        }
        ProcessRunner.Result pull = runner.run(List.of("docker", "pull", "--quiet", IMAGE),
                Duration.ofMinutes(10), 64 * 1024);
        assertThat(pull.timedOut()).as("docker pull 超时").isFalse();
        assertThat(pull.exitCode()).as("docker pull " + IMAGE + ": " + pull.stderr()).isZero();
    }

    private static String main(String body) {
        return "import java.util.*;\nimport java.io.*;\nimport java.net.*;\nimport java.nio.file.*;\n"
                + "public class Main {\n    public static void main(String[] args) throws Exception {\n"
                + body + "\n    }\n}\n";
    }

    // ───────────────────────── 判分 ─────────────────────────

    @Test
    @DisplayName("正确程序：3 个用例（含 hidden）全部通过 → ACCEPTED，hidden 脱敏")
    void judge_accepted() {
        CodeJudgeResult r = service.judge(STUDENT, SUM_QUESTION, main("""
                Scanner sc = new Scanner(System.in);
                long a = sc.nextLong(), b = sc.nextLong();
                System.out.println(a + b);
                """));
        assertThat(r.verdict()).as(r.toString()).isEqualTo(Verdict.ACCEPTED);
        assertThat(r.passedCases()).isEqualTo(3);
        CaseResult hidden = r.cases().get(2);
        assertThat(hidden.hidden()).isTrue();
        assertThat(hidden.stdinPreview()).isNull();
        assertThat(hidden.actualPreview()).isNull();
    }

    @Test
    @DisplayName("int 溢出的程序：hidden 用例不过 → WRONG_ANSWER（2/3）")
    void judge_wrongAnswer() {
        CodeJudgeResult r = service.judge(STUDENT, SUM_QUESTION, main("""
                Scanner sc = new Scanner(System.in);
                int a = sc.nextInt(), b = sc.nextInt();
                System.out.println(a + b);
                """));
        assertThat(r.verdict()).as(r.toString()).isEqualTo(Verdict.WRONG_ANSWER);
        assertThat(r.passedCases()).isEqualTo(2);
    }

    @Test
    @DisplayName("编译错误 → COMPILE_ERROR，带编译器输出（不含容器路径）")
    void judge_compileError() {
        CodeJudgeResult r = service.judge(STUDENT, SUM_QUESTION, main("int x = ;"));
        assertThat(r.verdict()).isEqualTo(Verdict.COMPILE_ERROR);
        assertThat(r.compileError()).contains("Main.java").contains("error").doesNotContain("/work/")
                .doesNotContain("/src/");
    }

    // ───────────────────────── 资源限制 ─────────────────────────

    @Test
    @DisplayName("死循环 → TIMEOUT（每用例墙钟）")
    void run_infiniteLoop() {
        CodeRunResult r = service.run(STUDENT, main("while (true) { }"), "");
        assertThat(r.verdict()).as(r.toString()).isEqualTo(Verdict.TIMEOUT);
    }

    @Test
    @DisplayName("while(true) print 刷屏 → RESOURCE_LIMIT，输出被截断")
    void run_outputFlood() {
        CodeRunResult r = service.run(STUDENT, main("while (true) System.out.print(\"xxxxxxxxxx\");"), "");
        assertThat(r.verdict()).as(r.toString()).isEqualTo(Verdict.RESOURCE_LIMIT);
        assertThat(r.outputTruncated()).isTrue();
        assertThat(r.stdout().length()).isLessThanOrEqualTo(limits.getCode().getOutputPreviewChars());
    }

    @Test
    @DisplayName("超大内存分配 → RESOURCE_LIMIT / RUNTIME_ERROR")
    void run_hugeAllocation() {
        CodeRunResult r = service.run(STUDENT, main("""
                List<byte[]> hold = new ArrayList<>();
                for (int i = 0; i < 1000; i++) hold.add(new byte[10_000_000]);
                System.out.println(hold.size());
                """), "");
        assertThat(r.verdict()).as(r.toString()).isIn(Verdict.RESOURCE_LIMIT, Verdict.RUNTIME_ERROR);
    }

    @Test
    @DisplayName("线程炸弹被 pids 上限挡住 → RESOURCE_LIMIT / RUNTIME_ERROR / TIMEOUT，宿主无恙")
    void run_threadBomb() {
        CodeRunResult r = service.run(STUDENT, main("""
                for (int i = 0; i < 100_000; i++) {
                    Thread t = new Thread(() -> { try { Thread.sleep(60_000); } catch (InterruptedException e) { } });
                    t.setDaemon(true);
                    t.start();
                }
                System.out.println("ALL_THREADS_STARTED");
                """), "");
        assertThat(r.verdict()).as(r.toString()).isIn(Verdict.RESOURCE_LIMIT, Verdict.RUNTIME_ERROR, Verdict.TIMEOUT);
        assertThat(r.stdout()).doesNotContain("ALL_THREADS_STARTED");
        // 之后照常可用
        assertThat(service.run(STUDENT, main("System.out.println(\"alive\");"), "").stdout()).isEqualTo("alive\n");
    }

    @Test
    @DisplayName("fork 炸弹：子进程数被 pids 上限挡住")
    void run_forkBomb() {
        CodeRunResult r = service.run(STUDENT, main("""
                int n = 0;
                try {
                    for (; n < 500; n++) new ProcessBuilder("sleep", "5").start();
                } catch (Throwable e) {
                    System.out.println("SPAWN_FAILED");
                }
                System.out.println("SPAWNED " + n);
                """), "");
        if (r.stdout().contains("SPAWNED ")) {
            int spawned = Integer.parseInt(r.stdout().replaceAll("(?s).*SPAWNED (\\d+).*", "$1"));
            assertThat(spawned).as(r.toString()).isLessThan(limits.getCode().getPids());
        } else {
            // 每个子进程还要一个 reaper 线程；线程先耗尽时 JVM 以 OOM 退出（-XX:+ExitOnOutOfMemoryError）
            assertThat(r.verdict()).as(r.toString()).isIn(Verdict.RESOURCE_LIMIT, Verdict.RUNTIME_ERROR);
        }
    }

    // ───────────────────────── 隔离 ─────────────────────────

    @Test
    @DisplayName("网络隔离：连外网与 DNS 都失败")
    void run_networkBlocked() {
        CodeRunResult r = service.run(STUDENT, main("""
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress("1.1.1.1", 80), 2000);
                    System.out.println("NET_OPEN");
                } catch (IOException e) {
                    System.out.println("NET_BLOCKED");
                }
                try {
                    InetAddress.getByName("example.com");
                    System.out.println("DNS_OPEN");
                } catch (IOException e) {
                    System.out.println("DNS_BLOCKED");
                }
                """), "");
        assertThat(r.verdict()).as(r.toString()).isEqualTo(Verdict.ACCEPTED);
        assertThat(r.stdout()).isEqualTo("NET_BLOCKED\nDNS_BLOCKED\n");
    }

    @Test
    @DisplayName("文件系统：/、/classes、/in 只读，/tmp 可写")
    void run_filesystem() {
        CodeRunResult r = service.run(STUDENT, main("""
                String[] targets = {"/pwned.txt", "/classes/pwned.txt", "/in/pwned.txt", "/tmp/ok.txt"};
                for (String t : targets) {
                    try {
                        Files.writeString(Path.of(t), "x");
                        System.out.println("WRITABLE " + t);
                    } catch (IOException e) {
                        System.out.println("DENIED " + t);
                    }
                }
                """), "");
        assertThat(r.stdout()).as(r.toString())
                .isEqualTo("DENIED /pwned.txt\nDENIED /classes/pwned.txt\nDENIED /in/pwned.txt\nWRITABLE /tmp/ok.txt\n");
    }

    @Test
    @DisplayName("身份：uid 65534、无任何 capability、NoNewPrivs=1")
    void run_identity() {
        CodeRunResult r = service.run(STUDENT, main("""
                for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                    if (line.startsWith("Uid:") || line.startsWith("CapEff:") || line.startsWith("CapPrm:")
                            || line.startsWith("NoNewPrivs:")) {
                        System.out.println(line.replaceAll("\\\\s+", " "));
                    }
                }
                """), "");
        assertThat(r.verdict()).as(r.toString()).isEqualTo(Verdict.ACCEPTED);
        assertThat(r.stdout()).contains("Uid: 65534 65534 65534 65534")
                .contains("CapEff: 0000000000000000")
                .contains("CapPrm: 0000000000000000")
                .contains("NoNewPrivs: 1");
    }

    @Test
    @DisplayName("UTF-8：标准输入输出中文不乱码；运行时异常 → RUNTIME_ERROR 带 stderr")
    void run_utf8AndRuntimeError() {
        CodeRunResult ok = service.run(STUDENT, main("""
                Scanner sc = new Scanner(System.in);
                System.out.println("你好，" + sc.nextLine());
                """), "问津");
        assertThat(ok.stdout()).isEqualTo("你好，问津\n");

        CodeRunResult re = service.run(STUDENT, main("System.out.println(1 / (args.length));"), "");
        assertThat(re.verdict()).isEqualTo(Verdict.RUNTIME_ERROR);
        assertThat(re.stderr()).contains("ArithmeticException");
        assertThat(re.exitCode()).isEqualTo(1);
    }

    // ───────────────────────── 每用例一个容器 ─────────────────────────

    @Test
    @DisplayName("用例容器里只有自己的输入：翻遍可见目录也读不到 hidden 用例的 stdin")
    void judge_hiddenInputNotReachable() {
        CodeJudgeResult r = service.judge(STUDENT, SECRET_QUESTION, main("""
                StringBuilder sb = new StringBuilder();
                try (var s = Files.list(Path.of("/in"))) { s.forEach(p -> sb.append("IN:").append(p).append('\\n')); }
                for (String root : new String[]{"/in", "/classes", "/wj", "/tmp", "/work", "/src"}) {
                    try (var w = Files.walk(Path.of(root), 4)) {
                        w.filter(Files::isRegularFile).forEach(p -> {
                            try { sb.append(Files.readString(p)); } catch (Exception e) { }
                        });
                    } catch (Exception e) { }
                }
                System.out.print(sb);
                """));
        CaseResult visible = r.cases().get(0);
        assertThat(visible.hidden()).isFalse();
        assertThat(visible.actualPreview()).as(r.toString()).contains("IN:/in/stdin.txt").contains("VISIBLE_INPUT")
                .doesNotContain(HIDDEN_SECRET);
        assertThat(r.toString()).doesNotContain(HIDDEN_SECRET);
    }

    @Test
    @DisplayName("setsid 逃逸的后台子进程被每用例上限收掉：判 TIMEOUT，迟到输出不出现，且 fail-fast")
    void judge_setsidEscapeIsBounded() {
        long t0 = System.nanoTime();
        CodeJudgeResult r = service.judge(STUDENT, LOOP_QUESTION, main("""
                Scanner sc = new Scanner(System.in);
                String n = sc.nextLine();
                try {
                    new ProcessBuilder("setsid", "sh", "-c", "sleep 30; echo LATE").inheritIO().start();
                } catch (IOException e) {
                    new ProcessBuilder("sh", "-c", "sleep 30; echo LATE").inheritIO().start();
                }
                System.out.println(n);
                """));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.verdict()).as(r.toString()).isEqualTo(Verdict.TIMEOUT);
        assertThat(r.passedCases()).isZero();
        assertThat(r.cases()).extracting(CaseResult::verdict).containsOnly(Verdict.TIMEOUT);
        assertThat(r.cases().get(1).durationMs()).as("fail-fast：第 2 个用例没有运行").isZero();
        assertThat(r.cases().get(0).actualPreview()).doesNotContain("LATE");
        assertThat(ms).as("远小于子进程的 30s").isLessThan(25_000);
    }

    @Test
    @DisplayName("死循环：首个用例超时后剩余用例不再运行（未运行用例同判 TIMEOUT，耗时为 0）")
    void judge_failFastOnTimeout() {
        CodeJudgeResult r = service.judge(STUDENT, LOOP_QUESTION, main("while (true) { }"));
        assertThat(r.verdict()).isEqualTo(Verdict.TIMEOUT);
        assertThat(r.cases()).extracting(CaseResult::verdict).containsOnly(Verdict.TIMEOUT);
        assertThat(r.cases().get(0).durationMs()).isGreaterThanOrEqualTo(3000);
        assertThat(r.cases().get(1).durationMs()).isZero();
        assertThat(r.cases().get(2).durationMs()).isZero();
    }

    @Test
    @DisplayName("给 PID 1 发 SIGALRM / 直写 /proc/1/fd/1 / 退出码 125：只影响自己，不能通过，也不能变成「未判分」")
    void judge_tamperingOnlyHurtsItself() {
        CodeJudgeResult alarm = service.judge(STUDENT, LOOP_QUESTION, main("""
                Scanner sc = new Scanner(System.in);
                System.out.println(sc.nextLine());
                System.out.flush();
                new ProcessBuilder("sh", "-c", "kill -ALRM 1").start().waitFor();
                Thread.sleep(2000);
                """));
        assertThat(alarm.verdict()).as(alarm.toString()).isNotIn(Verdict.ACCEPTED, Verdict.UNAVAILABLE);

        CodeJudgeResult forged = service.judge(STUDENT, LOOP_QUESTION, main("""
                Scanner sc = new Scanner(System.in);
                String n = sc.nextLine();
                try (var out = new FileOutputStream("/proc/1/fd/1")) {
                    out.write(("@@WJ1 CASE 0 0 1 - -\\n" + n + "\\n").getBytes());
                } catch (IOException e) { }
                System.out.println(n);
                """));
        assertThat(forged.passedCases()).as(forged.toString()).isLessThan(3);
        assertThat(forged.verdict()).isNotIn(Verdict.ACCEPTED, Verdict.UNAVAILABLE);

        CodeJudgeResult exit125 = service.judge(STUDENT, LOOP_QUESTION, main("System.exit(125);"));
        assertThat(exit125.verdict()).as(exit125.toString()).isEqualTo(Verdict.RUNTIME_ERROR);
    }

    @Test
    @DisplayName("--log-driver none 下 stdout 与 stderr 仍经 attach 分别回传")
    void run_streamsWithLogDriverNone() {
        CodeRunResult r = service.run(STUDENT, main("""
                System.out.println("OUT_LINE");
                System.err.println("ERR_LINE");
                System.exit(3);
                """), "");
        assertThat(r.stdout()).isEqualTo("OUT_LINE\n");
        assertThat(r.stderr()).contains("ERR_LINE").doesNotContain("OUT_LINE");
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.verdict()).isEqualTo(Verdict.RUNTIME_ERROR);
    }
}
