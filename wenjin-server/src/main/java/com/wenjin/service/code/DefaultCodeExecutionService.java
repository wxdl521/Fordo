package com.wenjin.service.code;

import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.config.LimitsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@link CodeExecutionService} 门面：开关/可用性 → 源码校验 → （judge：读用例）→ 每学生限流 → 全局并发许可 →
 * 沙箱执行 → verdict 归并、输出比对、截断与 hidden 脱敏。
 *
 * <p><b>失败安全</b>：任何基础设施问题（后端不可用、执行异常、等不到许可）都返回 {@link Verdict#UNAVAILABLE}，
 * 绝不抛给调用方。日志不含源码、输入或输出原文。</p>
 */
@Service
public class DefaultCodeExecutionService implements CodeExecutionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCodeExecutionService.class);

    static final Pattern CLASS_MAIN = Pattern.compile("\\bclass\\s+Main\\b");
    static final Pattern MAIN_METHOD = Pattern.compile(
            "\\b(?:public\\s+static|static\\s+public|static)\\s+(?:final\\s+)?void\\s+main\\s*\\(");
    static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;");

    /** 多用例归并的优先级（越靠前越优先）：编译错 > 超时 > 资源 > 运行错 > 答案错。 */
    static final List<Verdict> PRECEDENCE = List.of(
            Verdict.COMPILE_ERROR, Verdict.TIMEOUT, Verdict.RESOURCE_LIMIT, Verdict.RUNTIME_ERROR, Verdict.WRONG_ANSWER);

    static final String MSG_DISABLED = "代码运行功能未开启";
    static final String MSG_UNAVAILABLE = "代码运行服务暂不可用，请稍后再试";
    static final String MSG_BUSY = "当前运行的代码较多，请稍后再试";

    private final FeatureFlagsProperties flags;
    private final LimitsProperties limits;
    private final CodeSandbox sandbox;
    private final Supplier<CodeTestCaseSource> caseSource;
    private final StudentRateLimiter rateLimiter;
    private final Semaphore permits;

    @Autowired
    public DefaultCodeExecutionService(FeatureFlagsProperties flags, LimitsProperties limits, CodeSandbox sandbox,
                                       ObjectProvider<CodeTestCaseSource> caseSource, Clock clock) {
        this(flags, limits, sandbox, caseSource::getIfAvailable, clock);
    }

    DefaultCodeExecutionService(FeatureFlagsProperties flags, LimitsProperties limits, CodeSandbox sandbox,
                                Supplier<CodeTestCaseSource> caseSource, Clock clock) {
        this.flags = flags;
        this.limits = limits;
        this.sandbox = sandbox;
        this.caseSource = caseSource;
        this.rateLimiter = new StudentRateLimiter(clock, () -> code().getPerStudentPerHour());
        this.permits = new Semaphore(Math.max(1, code().getMaxConcurrent()), true);
    }

    private LimitsProperties.Code code() {
        return limits.getCode();
    }

    // ───────────────────────── available ─────────────────────────

    @Override
    public boolean available() {
        return flags.isCodeCheckEnabled() && sandboxAvailable();
    }

    private boolean sandboxAvailable() {
        try {
            return sandbox.available();
        } catch (RuntimeException e) {
            log.warn("代码沙箱可用性检查异常：{}", e.getClass().getSimpleName());
            return false;
        }
    }

    // ───────────────────────── run ─────────────────────────

    @Override
    public CodeRunResult run(long studentId, String source, String stdin) {
        long t0 = System.nanoTime();
        try {
            if (!flags.isCodeCheckEnabled()) {
                return runRefusal(Verdict.UNAVAILABLE, MSG_DISABLED, t0);
            }
            String invalid = validate(source);
            if (invalid != null) {
                return runRefusal(Verdict.REJECTED, invalid, t0);
            }
            String in = stdin == null ? "" : stdin;
            if (in.length() > code().getStdinMaxChars()) {
                return runRefusal(Verdict.REJECTED, "标准输入过长（最多 " + code().getStdinMaxChars() + " 字）", t0);
            }
            if (!sandboxAvailable()) {
                return runRefusal(Verdict.UNAVAILABLE, MSG_UNAVAILABLE, t0);
            }
            Execution ex = execute(studentId, source, List.of(in));
            if (ex.refusal() != null) {
                return runRefusal(ex.refusal(), ex.message(), t0);
            }
            CodeRunResult r = toRunResult(ex.result(), t0);
            log.info("代码运行 studentId={} backend={} verdict={} durationMs={}",
                    studentId, sandbox.name(), r.verdict(), r.durationMs());
            return r;
        } catch (RuntimeException e) {
            log.warn("代码运行异常 studentId={}：{}", studentId, e.getClass().getSimpleName());
            return runRefusal(Verdict.UNAVAILABLE, MSG_UNAVAILABLE, t0);
        }
    }

    private CodeRunResult toRunResult(SandboxResult r, long t0) {
        int previewChars = code().getOutputPreviewChars();
        switch (r.status()) {
            case COMPILE_ERROR -> {
                return new CodeRunResult(Verdict.COMPILE_ERROR, "", "", null, compileError(r.compileOutput()),
                        false, elapsed(t0), "编译失败");
            }
            case COMPILE_TIMEOUT -> {
                return new CodeRunResult(Verdict.TIMEOUT, "", "", null,
                        "编译超时（超过 " + code().getCompileTimeoutSeconds() + " 秒）", false, elapsed(t0), "编译超时");
            }
            case FAILED -> {
                log.warn("代码沙箱执行失败 backend={} detail={}", sandbox.name(), r.detail());
                return runRefusal(Verdict.UNAVAILABLE, MSG_UNAVAILABLE, t0);
            }
            default -> {
                // COMPLETED / TIMEOUT
            }
        }
        SandboxResult.CaseRun c = r.cases().isEmpty() ? null : r.cases().get(0);
        if (c == null) {
            Verdict v = r.status() == SandboxResult.Status.TIMEOUT ? Verdict.TIMEOUT : Verdict.RUNTIME_ERROR;
            return new CodeRunResult(v, "", "", null, null, false, elapsed(t0), message(v));
        }
        Verdict v = caseVerdict(c);
        CodeOutputs.Preview out = CodeOutputs.preview(c.stdout(), previewChars);
        CodeOutputs.Preview err = CodeOutputs.preview(c.stderr(), previewChars);
        boolean truncated = out.truncated() || err.truncated() || c.stdoutExceeded() || c.stderrExceeded();
        return new CodeRunResult(v, out.text(), err.text(), c.exitCode(), null, truncated, elapsed(t0), message(v));
    }

    private CodeRunResult runRefusal(Verdict v, String message, long t0) {
        return new CodeRunResult(v, "", "", null, null, false, elapsed(t0), message);
    }

    // ───────────────────────── judge ─────────────────────────

    @Override
    public CodeJudgeResult judge(long studentId, long questionId, String source) {
        long t0 = System.nanoTime();
        try {
            if (!flags.isCodeCheckEnabled()) {
                return judgeRefusal(Verdict.UNAVAILABLE, MSG_DISABLED, 0, t0);
            }
            String invalid = validate(source);
            if (invalid != null) {
                return judgeRefusal(Verdict.REJECTED, invalid, 0, t0);
            }
            if (!sandboxAvailable()) {
                return judgeRefusal(Verdict.UNAVAILABLE, MSG_UNAVAILABLE, 0, t0);
            }
            CodeTestCaseSource src = caseSource.get();
            if (src == null) {
                log.warn("没有 CodeTestCaseSource 实现，无法判分 questionId={}", questionId);
                return judgeRefusal(Verdict.UNAVAILABLE, MSG_UNAVAILABLE, 0, t0);
            }
            List<CodeTestCase> cases = new ArrayList<>(nullSafe(src.casesFor(questionId)));
            if (cases.isEmpty()) {
                return judgeRefusal(Verdict.REJECTED, "这道题还没有可用的测试用例", 0, t0);
            }
            if (cases.size() > code().getMaxCases()) {
                log.warn("编程题用例数超过上限，拒绝判分 questionId={} cases={} max={}",
                        questionId, cases.size(), code().getMaxCases());
                return judgeRefusal(Verdict.REJECTED, "这道题的测试用例数超过上限，请联系老师", cases.size(), t0);
            }
            cases.sort(Comparator.comparingInt(CodeTestCase::seq));
            List<String> stdins = cases.stream().map(c -> c.stdin() == null ? "" : c.stdin()).toList();
            Execution ex = execute(studentId, source, stdins);
            if (ex.refusal() != null) {
                return judgeRefusal(ex.refusal(), ex.message(), cases.size(), t0);
            }
            CodeJudgeResult r = toJudgeResult(ex.result(), cases, t0);
            log.info("代码判分 studentId={} questionId={} backend={} verdict={} passed={}/{} durationMs={}",
                    studentId, questionId, sandbox.name(), r.verdict(), r.passedCases(), r.totalCases(), r.durationMs());
            return r;
        } catch (RuntimeException e) {
            log.warn("代码判分异常 studentId={} questionId={}：{}", studentId, questionId, e.getClass().getSimpleName());
            return judgeRefusal(Verdict.UNAVAILABLE, MSG_UNAVAILABLE, 0, t0);
        }
    }

    private CodeJudgeResult toJudgeResult(SandboxResult r, List<CodeTestCase> cases, long t0) {
        int total = cases.size();
        switch (r.status()) {
            case COMPILE_ERROR -> {
                return new CodeJudgeResult(Verdict.COMPILE_ERROR, 0, total, compileError(r.compileOutput()),
                        List.of(), elapsed(t0), "编译失败");
            }
            case COMPILE_TIMEOUT -> {
                return new CodeJudgeResult(Verdict.TIMEOUT, 0, total,
                        "编译超时（超过 " + code().getCompileTimeoutSeconds() + " 秒）", List.of(), elapsed(t0), "编译超时");
            }
            case FAILED -> {
                log.warn("代码沙箱执行失败 backend={} detail={}", sandbox.name(), r.detail());
                return judgeRefusal(Verdict.UNAVAILABLE, MSG_UNAVAILABLE, total, t0);
            }
            default -> {
                // COMPLETED / TIMEOUT
            }
        }
        // 缺失（未运行）用例绝不当作通过：整体超时 → TIMEOUT；沙箱 fail-fast（某用例超时/超资源后停止）→
        // 与触发用例同一 verdict；其余（运行器被破坏）→ RUNTIME_ERROR。
        Verdict missing = missingCaseVerdict(r);
        SandboxResult.CaseRun[] byIndex = new SandboxResult.CaseRun[total];
        for (SandboxResult.CaseRun c : r.cases()) {
            if (c.index() >= 0 && c.index() < total) {
                byIndex[c.index()] = c;
            }
        }
        int previewChars = code().getOutputPreviewChars();
        List<CaseResult> results = new ArrayList<>(total);
        int passed = 0;
        Verdict worst = null;
        for (int i = 0; i < total; i++) {
            CodeTestCase tc = cases.get(i);
            SandboxResult.CaseRun run = byIndex[i];
            Verdict v;
            if (run == null) {
                v = missing;
            } else {
                v = caseVerdict(run);
                if (v == Verdict.ACCEPTED && !CodeOutputs.outputsMatch(tc.expectedStdout(), run.stdout())) {
                    v = Verdict.WRONG_ANSWER;
                }
            }
            boolean ok = v == Verdict.ACCEPTED;
            if (ok) {
                passed++;
            } else if (worst == null || PRECEDENCE.indexOf(v) < PRECEDENCE.indexOf(worst)) {
                worst = v;
            }
            long ms = run == null ? 0 : run.durationMs();
            if (tc.hidden()) {
                results.add(new CaseResult(tc.seq(), ok, true, v, null, null, null, ms));
            } else {
                results.add(new CaseResult(tc.seq(), ok, false, v,
                        CodeOutputs.preview(tc.stdin(), previewChars).text(),
                        CodeOutputs.preview(tc.expectedStdout(), previewChars).text(),
                        run == null ? null : CodeOutputs.preview(run.stdout(), previewChars).text(),
                        ms));
            }
        }
        Verdict overall = worst == null ? Verdict.ACCEPTED : worst;
        return new CodeJudgeResult(overall, passed, total, null, results, elapsed(t0), message(overall));
    }

    static Verdict missingCaseVerdict(SandboxResult r) {
        if (r.status() == SandboxResult.Status.TIMEOUT) {
            return Verdict.TIMEOUT;
        }
        if (!r.cases().isEmpty()) {
            Verdict last = caseVerdict(r.cases().get(r.cases().size() - 1));
            if (last == Verdict.TIMEOUT || last == Verdict.RESOURCE_LIMIT) {
                return last;
            }
        }
        return Verdict.RUNTIME_ERROR;
    }

    private CodeJudgeResult judgeRefusal(Verdict v, String message, int total, long t0) {
        return new CodeJudgeResult(v, 0, total, null, List.of(), elapsed(t0), message);
    }

    // ───────────────────────── 共用 ─────────────────────────

    private record Execution(SandboxResult result, Verdict refusal, String message) {
    }

    /** 限流 → 并发许可 → 沙箱。等不到许可时退回限流名额。 */
    private Execution execute(long studentId, String source, List<String> stdins) {
        if (!rateLimiter.tryAcquire(studentId)) {
            return new Execution(null, Verdict.RATE_LIMITED,
                    "运行太频繁了：每小时最多 " + code().getPerStudentPerHour() + " 次，请稍后再试");
        }
        boolean acquired;
        try {
            acquired = permits.tryAcquire(Math.max(0, code().getQueueWaitSeconds()), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            rateLimiter.refund(studentId);
            log.info("代码沙箱并发已满，拒绝 studentId={}", studentId);
            return new Execution(null, Verdict.UNAVAILABLE, MSG_BUSY);
        }
        try {
            SandboxResult r = sandbox.execute(new SandboxRequest(source, stdins, SandboxLimits.from(code())));
            return new Execution(r == null ? SandboxResult.failed("null result") : r, null, null);
        } finally {
            permits.release();
        }
    }

    /** 单个用例在比对之前的 verdict（同一用例内：输出超限优先于超时，死循环刷屏判资源超限）。 */
    static Verdict caseVerdict(SandboxResult.CaseRun c) {
        if (c.stdoutExceeded() || c.stderrExceeded()) {
            return Verdict.RESOURCE_LIMIT;
        }
        if (c.timedOut()) {
            return Verdict.TIMEOUT;
        }
        if (c.exitCode() == 137 || CodeOutputs.looksLikeOutOfMemory(c.stderr())) {
            return Verdict.RESOURCE_LIMIT;
        }
        if (c.exitCode() != 0) {
            return Verdict.RUNTIME_ERROR;
        }
        return Verdict.ACCEPTED;
    }

    /** 源码校验；合法返回 null，否则返回给学生的一句话原因。 */
    String validate(String source) {
        if (source == null || source.isBlank()) {
            return "代码为空";
        }
        if (source.length() > code().getSourceMaxChars()) {
            return "代码过长（最多 " + code().getSourceMaxChars() + " 字）";
        }
        if (!CLASS_MAIN.matcher(source).find()) {
            return "目前只支持 Java：请把入口类命名为 Main（public class Main）";
        }
        if (!MAIN_METHOD.matcher(source).find()) {
            return "缺少入口方法 public static void main(String[] args)";
        }
        if (PACKAGE_DECL.matcher(source).find()) {
            return "请删除 package 声明后再运行";
        }
        return null;
    }

    private String compileError(String raw) {
        return CodeOutputs.preview(CodeOutputs.cleanCompileOutput(raw), code().getCompileErrorChars()).text();
    }

    private String message(Verdict v) {
        return switch (v) {
            case ACCEPTED -> "运行通过";
            case WRONG_ANSWER -> "输出与期望不一致";
            case COMPILE_ERROR -> "编译失败";
            case RUNTIME_ERROR -> "运行时出错";
            case TIMEOUT -> "运行超时（每个用例最多 " + code().getRunTimeoutSeconds() + " 秒）";
            case RESOURCE_LIMIT -> "超出资源限制（输出或内存过大）";
            case REJECTED -> "代码不符合要求";
            case RATE_LIMITED -> "运行太频繁了，请稍后再试";
            case UNAVAILABLE -> MSG_UNAVAILABLE;
        };
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static long elapsed(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
