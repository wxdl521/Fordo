package com.wenjin.service.code;

import java.util.List;

/**
 * 代码执行门面（方向 6）：在隔离沙箱中编译/运行学生代码。
 * <p>v1 仅支持 Java，源码约定入口为 {@code public class Main}。所有限额来自 {@code wenjin.limits.code.*}，
 * 沙箱后端由 {@code wenjin.code-check.backend} 选择；开关 {@code wenjin.features.code-check-enabled} 关闭或
 * 后端不可用时 {@link #available()} 为 false，调用方必须先判断。
 * <p>本接口只返回结构化结果，调用方不得把源码原文或完整输出回注给大模型。
 */
public interface CodeExecutionService {

    /** 开关开启且沙箱后端可用。 */
    boolean available();

    /** 自由运行：编译并以给定标准输入运行一次（伴侣「帮我跑一下」）。不写任何学习证据。 */
    CodeRunResult run(long studentId, String source, String stdin);

    /** 按编程题测试用例判分（练习提交 / 伴侣带 questionId 检查）。本方法本身不写 answer_record。 */
    CodeJudgeResult judge(long studentId, long questionId, String source);

    enum Verdict {
        /** 全部用例通过（judge）/ 正常结束（run）。 */
        ACCEPTED,
        WRONG_ANSWER,
        COMPILE_ERROR,
        RUNTIME_ERROR,
        TIMEOUT,
        /** 输出超限、内存超限等资源类终止。 */
        RESOURCE_LIMIT,
        /** 源码不合规：超长、缺少 Main、非 Java 等，未执行。 */
        REJECTED,
        /** 学生触发频率上限，未执行。 */
        RATE_LIMITED,
        /** 开关关闭或沙箱不可用，未执行。 */
        UNAVAILABLE
    }

    /**
     * @param stdout          截断后的标准输出（≤ limits.code.output-preview-chars）
     * @param stderr          截断后的标准错误
     * @param compileError    编译失败时的编译器输出（截断），否则 null
     * @param message         面向学生的一句话说明（REJECTED / RATE_LIMITED / UNAVAILABLE 时必填）
     */
    record CodeRunResult(Verdict verdict,
                         String stdout,
                         String stderr,
                         Integer exitCode,
                         String compileError,
                         boolean outputTruncated,
                         long durationMs,
                         String message) {
    }

    /**
     * @param cases 每个用例的结果；hidden 用例不暴露输入/期望/实际输出
     */
    record CodeJudgeResult(Verdict verdict,
                           int passedCases,
                           int totalCases,
                           String compileError,
                           List<CaseResult> cases,
                           long durationMs,
                           String message) {
        public CodeJudgeResult {
            cases = cases == null ? List.of() : List.copyOf(cases);
        }

        public boolean accepted() {
            return verdict == Verdict.ACCEPTED;
        }
    }

    /**
     * @param stdinPreview    hidden 用例为 null
     * @param expectedPreview hidden 用例为 null
     * @param actualPreview   hidden 用例为 null
     */
    record CaseResult(int index,
                      boolean passed,
                      boolean hidden,
                      Verdict verdict,
                      String stdinPreview,
                      String expectedPreview,
                      String actualPreview,
                      long durationMs) {
    }
}
