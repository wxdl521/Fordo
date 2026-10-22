package com.wenjin.service.code;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import com.wenjin.service.code.CodeExecutionService.CaseResult;
import com.wenjin.service.code.CodeExecutionService.CodeJudgeResult;
import com.wenjin.service.code.CodeExecutionService.CodeRunResult;
import com.wenjin.service.code.CodeExecutionService.Verdict;
import com.wenjin.service.code.SandboxResult.CaseRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultCodeExecutionServiceTest {

    static final String OK_SOURCE = """
            import java.util.*;
            public class Main {
                public static void main(String[] args) {
                    Scanner sc = new Scanner(System.in);
                    System.out.println(sc.nextInt() + sc.nextInt());
                }
            }
            """;

    private FeatureFlagsProperties flags;
    private LimitsProperties limits;
    private FakeSandbox sandbox;
    private Map<Long, List<CodeTestCase>> caseTable;
    private MutableClock clock;
    private DefaultCodeExecutionService service;

    @BeforeEach
    void setUp() {
        flags = new FeatureFlagsProperties();
        limits = new LimitsProperties();
        sandbox = new FakeSandbox();
        caseTable = new HashMap<>();
        clock = new MutableClock(Instant.parse("2026-10-22T08:00:00Z"));
        service = newService();
    }

    private DefaultCodeExecutionService newService() {
        CodeTestCaseSource source = qid -> caseTable.getOrDefault(qid, List.of());
        return new DefaultCodeExecutionService(flags, limits, sandbox, () -> source, clock);
    }

    private static CaseRun ok(int i, String stdout) {
        return new CaseRun(i, 0, false, 10, stdout, false, "", false);
    }

    private static SandboxResult completed(CaseRun... cases) {
        return new SandboxResult(SandboxResult.Status.COMPLETED, "", List.of(cases), null);
    }

    // ───────────────────────── 开关 / 可用性 ─────────────────────────

    @Test
    @DisplayName("available = 开关开 && 后端可用；开关关时不探测后端")
    void available_gating() {
        assertThat(service.available()).isTrue();
        sandbox.available = false;
        assertThat(service.available()).isFalse();
        sandbox.available = true;
        flags.setCodeCheckEnabled(false);
        int probes = sandbox.availableCalls.get();
        assertThat(service.available()).isFalse();
        assertThat(sandbox.availableCalls.get()).isEqualTo(probes);
    }

    @Test
    @DisplayName("开关关闭 / 后端不可用 / available 抛异常 → UNAVAILABLE 且不执行")
    void unavailable_neverExecutes() {
        flags.setCodeCheckEnabled(false);
        assertThat(service.run(1L, OK_SOURCE, "1 2").verdict()).isEqualTo(Verdict.UNAVAILABLE);
        assertThat(service.judge(1L, 9L, OK_SOURCE).verdict()).isEqualTo(Verdict.UNAVAILABLE);

        flags.setCodeCheckEnabled(true);
        sandbox.available = false;
        CodeRunResult r = service.run(1L, OK_SOURCE, "1 2");
        assertThat(r.verdict()).isEqualTo(Verdict.UNAVAILABLE);
        assertThat(r.message()).isNotBlank();

        sandbox.available = true;
        sandbox.availableThrows = true;
        assertThat(service.run(1L, OK_SOURCE, "1 2").verdict()).isEqualTo(Verdict.UNAVAILABLE);
        assertThat(sandbox.requests).isEmpty();
    }

    @Test
    @DisplayName("后端执行 FAILED / 抛异常 → UNAVAILABLE（绝不抛到调用方）")
    void sandboxFailure_isUnavailable() {
        sandbox.next = req -> SandboxResult.failed("docker exploded");
        CodeRunResult r = service.run(1L, OK_SOURCE, "");
        assertThat(r.verdict()).isEqualTo(Verdict.UNAVAILABLE);
        assertThat(r.message()).doesNotContain("docker exploded");

        sandbox.next = req -> {
            throw new IllegalStateException("boom");
        };
        assertThat(service.run(1L, OK_SOURCE, "").verdict()).isEqualTo(Verdict.UNAVAILABLE);
        caseTable.put(5L, List.of(new CodeTestCase(1, "1 2", "3", false)));
        assertThat(service.judge(1L, 5L, OK_SOURCE).verdict()).isEqualTo(Verdict.UNAVAILABLE);
    }

    // ───────────────────────── 源码校验 ─────────────────────────

    @Test
    @DisplayName("源码校验：空、超长、缺 class Main、缺 static void main、带 package → REJECTED 且不执行")
    void validation_rejects() {
        assertRejected(null);
        assertRejected("   ");
        assertRejected("public class Main { public static void main(String[] a){} }" + " ".repeat(10_001));
        assertRejected("public class Foo { public static void main(String[] a){} }");
        assertRejected("public class Main { void main() {} }");
        assertRejected("package a.b;\npublic class Main { public static void main(String[] a){} }");
        assertRejected("print('hello')");
        assertThat(sandbox.requests).isEmpty();
        assertThat(service.judge(1L, 1L, "x").verdict()).isEqualTo(Verdict.REJECTED);
    }

    @Test
    @DisplayName("合法变体：非 public 的 Main、static public、final 参数")
    void validation_accepts() {
        sandbox.next = req -> completed(ok(0, ""));
        assertThat(service.run(1L, "class Main { static public void main(final String... a) {} }", null).verdict())
                .isEqualTo(Verdict.ACCEPTED);
        assertThat(sandbox.requests.get(0).stdins()).containsExactly("");
    }

    @Test
    @DisplayName("stdin 超过上限 → REJECTED")
    void stdin_tooLong() {
        limits.getCode().setStdinMaxChars(10);
        assertThat(service.run(1L, OK_SOURCE, "x".repeat(11)).verdict()).isEqualTo(Verdict.REJECTED);
    }

    private void assertRejected(String source) {
        CodeRunResult r = service.run(1L, source, "");
        assertThat(r.verdict()).as(String.valueOf(source)).isEqualTo(Verdict.REJECTED);
        assertThat(r.message()).isNotBlank();
    }

    // ───────────────────────── run 结果映射 ─────────────────────────

    @Test
    @DisplayName("run 正常：ACCEPTED、stdout/stderr 预览、退出码、请求携带限额快照")
    void run_accepted() {
        sandbox.next = req -> completed(new CaseRun(0, 0, false, 120, "3\n", false, "warn", false));
        CodeRunResult r = service.run(1L, OK_SOURCE, "1 2");
        assertThat(r.verdict()).isEqualTo(Verdict.ACCEPTED);
        assertThat(r.stdout()).isEqualTo("3\n");
        assertThat(r.stderr()).isEqualTo("warn");
        assertThat(r.exitCode()).isZero();
        assertThat(r.compileError()).isNull();
        assertThat(r.outputTruncated()).isFalse();
        SandboxRequest req = sandbox.requests.get(0);
        assertThat(req.source()).isEqualTo(OK_SOURCE);
        assertThat(req.stdins()).containsExactly("1 2");
        assertThat(req.limits()).isEqualTo(SandboxLimits.from(limits.getCode()));
    }

    @Test
    @DisplayName("run 截断：stdout 超预览字符数截断并标记；容器内超限也标记且判 RESOURCE_LIMIT")
    void run_truncation() {
        limits.getCode().setOutputPreviewChars(5);
        sandbox.next = req -> completed(ok(0, "abcdefghij"));
        CodeRunResult r = service.run(1L, OK_SOURCE, "");
        assertThat(r.verdict()).isEqualTo(Verdict.ACCEPTED);
        assertThat(r.stdout()).isEqualTo("abcde");
        assertThat(r.outputTruncated()).isTrue();

        sandbox.next = req -> completed(new CaseRun(0, 124, true, 3000, "xxxxxxxx", true, "", false));
        CodeRunResult flood = service.run(1L, OK_SOURCE, "");
        assertThat(flood.verdict()).isEqualTo(Verdict.RESOURCE_LIMIT);
        assertThat(flood.outputTruncated()).isTrue();
    }

    @Test
    @DisplayName("run 各类 verdict：编译错（截断、去路径）、编译超时、整体超时、运行错、OOM、被杀、缺用例")
    void run_verdicts() {
        limits.getCode().setCompileErrorChars(30);
        sandbox.next = req -> new SandboxResult(SandboxResult.Status.COMPILE_ERROR,
                "/work/Main.java:3: error: ';' expected\n" + "x".repeat(100), List.of(), null);
        CodeRunResult ce = service.run(1L, OK_SOURCE, "");
        assertThat(ce.verdict()).isEqualTo(Verdict.COMPILE_ERROR);
        assertThat(ce.compileError()).startsWith("Main.java:3: error").hasSize(30);
        assertThat(ce.exitCode()).isNull();

        sandbox.next = req -> new SandboxResult(SandboxResult.Status.COMPILE_TIMEOUT, "", List.of(), null);
        assertThat(service.run(1L, OK_SOURCE, "").verdict()).isEqualTo(Verdict.TIMEOUT);

        sandbox.next = req -> new SandboxResult(SandboxResult.Status.TIMEOUT, null, List.of(), null);
        assertThat(service.run(1L, OK_SOURCE, "").verdict()).isEqualTo(Verdict.TIMEOUT);

        sandbox.next = req -> completed(new CaseRun(0, 124, true, 3001, "", false, "", false));
        assertThat(service.run(1L, OK_SOURCE, "").verdict()).isEqualTo(Verdict.TIMEOUT);

        sandbox.next = req -> completed(new CaseRun(0, 1, false, 5, "", false,
                "Exception in thread \"main\" java.lang.ArithmeticException: / by zero", false));
        CodeRunResult re = service.run(1L, OK_SOURCE, "");
        assertThat(re.verdict()).isEqualTo(Verdict.RUNTIME_ERROR);
        assertThat(re.exitCode()).isEqualTo(1);
        assertThat(re.stderr()).contains("ArithmeticException");

        sandbox.next = req -> completed(new CaseRun(0, 3, false, 5, "", false,
                "Terminating due to java.lang.OutOfMemoryError: Java heap space", false));
        assertThat(service.run(1L, OK_SOURCE, "").verdict()).isEqualTo(Verdict.RESOURCE_LIMIT);

        sandbox.next = req -> completed(new CaseRun(0, 137, false, 5, "", false, "", false));
        assertThat(service.run(1L, OK_SOURCE, "").verdict()).isEqualTo(Verdict.RESOURCE_LIMIT);

        sandbox.next = req -> completed(new CaseRun(0, 0, false, 5, "", false, "e", true));
        assertThat(service.run(1L, OK_SOURCE, "").verdict()).isEqualTo(Verdict.RESOURCE_LIMIT);

        sandbox.next = req -> completed();
        assertThat(service.run(1L, OK_SOURCE, "").verdict()).isEqualTo(Verdict.RUNTIME_ERROR);
    }

    // ───────────────────────── judge ─────────────────────────

    @Test
    @DisplayName("judge 全过：ACCEPTED；按 seq 排序送入沙箱；比对规则宽容行尾空白与末尾空行")
    void judge_accepted() {
        caseTable.put(9L, List.of(
                new CodeTestCase(2, "10 20", "30", true),
                new CodeTestCase(1, "1 2", "3\n", false)));
        sandbox.next = req -> completed(ok(0, "3   \n\n"), ok(1, "30"));

        CodeJudgeResult r = service.judge(1L, 9L, OK_SOURCE);

        assertThat(sandbox.requests.get(0).stdins()).containsExactly("1 2", "10 20");
        assertThat(r.verdict()).isEqualTo(Verdict.ACCEPTED);
        assertThat(r.accepted()).isTrue();
        assertThat(r.passedCases()).isEqualTo(2);
        assertThat(r.totalCases()).isEqualTo(2);
        assertThat(r.cases()).extracting(CaseResult::index).containsExactly(1, 2);
        assertThat(r.cases()).allMatch(CaseResult::passed);
    }

    @Test
    @DisplayName("hidden 用例脱敏：输入 / 期望 / 实际一律 null；可见用例给预览（截断）")
    void judge_hiddenRedaction() {
        limits.getCode().setOutputPreviewChars(4);
        caseTable.put(9L, List.of(
                new CodeTestCase(1, "1 2 3 4 5", "3", false),
                new CodeTestCase(2, "SECRET-IN", "SECRET-OUT", true)));
        sandbox.next = req -> completed(ok(0, "wrong-output"), ok(1, "SECRET-ACTUAL"));

        CodeJudgeResult r = service.judge(1L, 9L, OK_SOURCE);

        assertThat(r.verdict()).isEqualTo(Verdict.WRONG_ANSWER);
        assertThat(r.passedCases()).isZero();
        CaseResult visible = r.cases().get(0);
        assertThat(visible.hidden()).isFalse();
        assertThat(visible.verdict()).isEqualTo(Verdict.WRONG_ANSWER);
        assertThat(visible.stdinPreview()).isEqualTo("1 2 ");
        assertThat(visible.expectedPreview()).isEqualTo("3");
        assertThat(visible.actualPreview()).isEqualTo("wron");
        CaseResult hidden = r.cases().get(1);
        assertThat(hidden.hidden()).isTrue();
        assertThat(hidden.passed()).isFalse();
        assertThat(hidden.stdinPreview()).isNull();
        assertThat(hidden.expectedPreview()).isNull();
        assertThat(hidden.actualPreview()).isNull();
        assertThat(r.toString()).doesNotContain("SECRET");
    }

    @Test
    @DisplayName("verdict 归并：编译错 > 超时 > 资源 > 运行错 > 答案错")
    void judge_precedence() {
        caseTable.put(9L, List.of(
                new CodeTestCase(1, "a", "1", false),
                new CodeTestCase(2, "b", "2", false),
                new CodeTestCase(3, "c", "3", false),
                new CodeTestCase(4, "d", "4", false),
                new CodeTestCase(5, "e", "5", false)));
        CaseRun wa = ok(0, "nope");
        CaseRun re = new CaseRun(1, 1, false, 5, "", false, "boom", false);
        CaseRun rl = new CaseRun(2, 0, false, 5, "x", true, "", false);
        CaseRun to = new CaseRun(3, 124, true, 3000, "", false, "", false);
        CaseRun ac = ok(4, "5");

        sandbox.next = req -> completed(wa, re, rl, to, ac);
        CodeJudgeResult all = service.judge(1L, 9L, OK_SOURCE);
        assertThat(all.verdict()).isEqualTo(Verdict.TIMEOUT);
        assertThat(all.passedCases()).isEqualTo(1);
        assertThat(all.cases()).extracting(CaseResult::verdict).containsExactly(
                Verdict.WRONG_ANSWER, Verdict.RUNTIME_ERROR, Verdict.RESOURCE_LIMIT, Verdict.TIMEOUT, Verdict.ACCEPTED);

        sandbox.next = req -> completed(wa, re, rl, ok(3, "4"), ac);
        assertThat(service.judge(1L, 9L, OK_SOURCE).verdict()).isEqualTo(Verdict.RESOURCE_LIMIT);
        sandbox.next = req -> completed(wa, re, ok(2, "3"), ok(3, "4"), ac);
        assertThat(service.judge(1L, 9L, OK_SOURCE).verdict()).isEqualTo(Verdict.RUNTIME_ERROR);
        sandbox.next = req -> completed(wa, ok(1, "2"), ok(2, "3"), ok(3, "4"), ac);
        assertThat(service.judge(1L, 9L, OK_SOURCE).verdict()).isEqualTo(Verdict.WRONG_ANSWER);

        sandbox.next = req -> new SandboxResult(SandboxResult.Status.COMPILE_ERROR, "err", List.of(), null);
        CodeJudgeResult ce = service.judge(1L, 9L, OK_SOURCE);
        assertThat(ce.verdict()).isEqualTo(Verdict.COMPILE_ERROR);
        assertThat(ce.compileError()).isEqualTo("err");
        assertThat(ce.passedCases()).isZero();
        assertThat(ce.totalCases()).isEqualTo(5);

        // 整体超时：已完成的照常判，缺失的记 TIMEOUT
        sandbox.next = req -> new SandboxResult(SandboxResult.Status.TIMEOUT, null, List.of(ok(0, "1")), null);
        CodeJudgeResult partial = service.judge(1L, 9L, OK_SOURCE);
        assertThat(partial.verdict()).isEqualTo(Verdict.TIMEOUT);
        assertThat(partial.passedCases()).isEqualTo(1);
        assertThat(partial.cases()).hasSize(5);

        // 运行器中途被破坏：缺失用例记 RUNTIME_ERROR（绝不当作通过）
        sandbox.next = req -> completed(ok(0, "1"));
        CodeJudgeResult broken = service.judge(1L, 9L, OK_SOURCE);
        assertThat(broken.verdict()).isEqualTo(Verdict.RUNTIME_ERROR);
        assertThat(broken.passedCases()).isEqualTo(1);
    }

    @Test
    @DisplayName("沙箱 fail-fast：首个超时/超资源用例之后未运行的用例按同一 verdict 计为未通过，绝不算通过")
    void judge_failFastMissingCases() {
        caseTable.put(9L, List.of(
                new CodeTestCase(1, "a", "1", false),
                new CodeTestCase(2, "b", "2", false),
                new CodeTestCase(3, "c", "3", true),
                new CodeTestCase(4, "d", "4", true)));
        CaseRun to = new CaseRun(1, 124, true, 3000, "", false, "", false);
        sandbox.next = req -> completed(ok(0, "1"), to);
        CodeJudgeResult t = service.judge(1L, 9L, OK_SOURCE);
        assertThat(t.verdict()).isEqualTo(Verdict.TIMEOUT);
        assertThat(t.passedCases()).isEqualTo(1);
        assertThat(t.cases()).extracting(CaseResult::verdict).containsExactly(
                Verdict.ACCEPTED, Verdict.TIMEOUT, Verdict.TIMEOUT, Verdict.TIMEOUT);
        assertThat(t.cases()).extracting(CaseResult::passed).containsExactly(true, false, false, false);

        CaseRun flood = new CaseRun(0, 0, false, 50, "x", true, "", false);
        sandbox.next = req -> completed(flood);
        CodeJudgeResult rl = service.judge(1L, 9L, OK_SOURCE);
        assertThat(rl.verdict()).isEqualTo(Verdict.RESOURCE_LIMIT);
        assertThat(rl.passedCases()).isZero();
        assertThat(rl.cases()).extracting(CaseResult::verdict).containsOnly(Verdict.RESOURCE_LIMIT);

        // 最后一个已运行用例只是答案错：缺失用例仍按运行器被破坏处理
        sandbox.next = req -> completed(ok(0, "wrong"));
        assertThat(service.judge(1L, 9L, OK_SOURCE).cases()).extracting(CaseResult::verdict)
                .containsExactly(Verdict.WRONG_ANSWER, Verdict.RUNTIME_ERROR, Verdict.RUNTIME_ERROR,
                        Verdict.RUNTIME_ERROR);
    }

    @Test
    @DisplayName("judge：无用例 / 用例超上限 → REJECTED，不执行也不计次；无 CodeTestCaseSource → UNAVAILABLE")
    void judge_caseGuards() {
        assertThat(service.judge(1L, 404L, OK_SOURCE).verdict()).isEqualTo(Verdict.REJECTED);
        limits.getCode().setMaxCases(2);
        caseTable.put(9L, List.of(new CodeTestCase(1, "", "", false), new CodeTestCase(2, "", "", false),
                new CodeTestCase(3, "", "", false)));
        assertThat(service.judge(1L, 9L, OK_SOURCE).verdict()).isEqualTo(Verdict.REJECTED);
        assertThat(sandbox.requests).isEmpty();

        DefaultCodeExecutionService noSource = new DefaultCodeExecutionService(flags, limits, sandbox, () -> null, clock);
        assertThat(noSource.judge(1L, 9L, OK_SOURCE).verdict()).isEqualTo(Verdict.UNAVAILABLE);
    }

    // ───────────────────────── 限流 ─────────────────────────

    @Test
    @DisplayName("每学生每小时上限：超出 RATE_LIMITED 且不执行；一小时后恢复；拒绝类请求不计次")
    void rateLimit() {
        limits.getCode().setPerStudentPerHour(2);
        sandbox.next = req -> completed(ok(0, ""));
        assertThat(service.run(1L, "bad", "").verdict()).isEqualTo(Verdict.REJECTED);
        assertThat(service.run(1L, OK_SOURCE, "").verdict()).isEqualTo(Verdict.ACCEPTED);
        assertThat(service.run(1L, OK_SOURCE, "").verdict()).isEqualTo(Verdict.ACCEPTED);
        CodeRunResult limited = service.run(1L, OK_SOURCE, "");
        assertThat(limited.verdict()).isEqualTo(Verdict.RATE_LIMITED);
        assertThat(limited.message()).contains("2");
        caseTable.put(9L, List.of(new CodeTestCase(1, "", "", false)));
        assertThat(service.judge(1L, 9L, OK_SOURCE).verdict()).isEqualTo(Verdict.RATE_LIMITED);
        assertThat(sandbox.requests).hasSize(2);

        assertThat(service.run(2L, OK_SOURCE, "").verdict()).isEqualTo(Verdict.ACCEPTED);

        clock.advance(Duration.ofMinutes(61));
        assertThat(service.run(1L, OK_SOURCE, "").verdict()).isEqualTo(Verdict.ACCEPTED);
    }

    // ───────────────────────── 并发 ─────────────────────────

    @Test
    @DisplayName("全局并发信号量：同时执行数不超过 max-concurrent；等不到许可 → UNAVAILABLE 并退回限流名额")
    void concurrency() throws Exception {
        limits.getCode().setMaxConcurrent(2);
        limits.getCode().setQueueWaitSeconds(1);
        limits.getCode().setPerStudentPerHour(3);
        service = newService();

        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        sandbox.next = req -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return completed(ok(0, ""));
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<CodeRunResult>> running = new ArrayList<>();
            running.add(pool.submit(() -> service.run(10L, OK_SOURCE, "")));
            running.add(pool.submit(() -> service.run(11L, OK_SOURCE, "")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            // 第三个请求等 1 秒拿不到许可
            CodeRunResult busy = service.run(12L, OK_SOURCE, "");
            assertThat(busy.verdict()).isEqualTo(Verdict.UNAVAILABLE);
            assertThat(busy.message()).contains("稍后");

            release.countDown();
            for (Future<CodeRunResult> f : running) {
                assertThat(f.get(5, TimeUnit.SECONDS).verdict()).isEqualTo(Verdict.ACCEPTED);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(maxInFlight.get()).isEqualTo(2);

        // 学生 12 的那次未执行：名额已退回，仍可用满 3 次
        sandbox.next = req -> completed(ok(0, ""));
        for (int i = 0; i < 3; i++) {
            assertThat(service.run(12L, OK_SOURCE, "").verdict()).isEqualTo(Verdict.ACCEPTED);
        }
        assertThat(service.run(12L, OK_SOURCE, "").verdict()).isEqualTo(Verdict.RATE_LIMITED);
    }

    @Test
    @DisplayName("许可在异常后也会释放")
    void permitReleasedOnException() {
        limits.getCode().setMaxConcurrent(1);
        limits.getCode().setQueueWaitSeconds(1);
        service = newService();
        sandbox.next = req -> {
            throw new IllegalStateException("x");
        };
        service.run(1L, OK_SOURCE, "");
        sandbox.next = req -> completed(ok(0, ""));
        assertThat(service.run(1L, OK_SOURCE, "").verdict()).isEqualTo(Verdict.ACCEPTED);
    }

    // ───────────────────────── fake ─────────────────────────

    static final class FakeSandbox implements CodeSandbox {
        volatile boolean available = true;
        volatile boolean availableThrows;
        final AtomicInteger availableCalls = new AtomicInteger();
        final List<SandboxRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile Function<SandboxRequest, SandboxResult> next = req -> completed(ok(0, ""));

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public boolean available() {
            availableCalls.incrementAndGet();
            if (availableThrows) {
                throw new IllegalStateException("probe failed");
            }
            return available;
        }

        @Override
        public SandboxResult execute(SandboxRequest request) {
            requests.add(request);
            return next.apply(request);
        }
    }
}
