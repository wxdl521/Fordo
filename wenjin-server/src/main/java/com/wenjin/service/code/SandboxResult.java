package com.wenjin.service.code;

import java.util.List;

/**
 * 沙箱原始结果（未比对、未脱敏、未截断到预览长度；输出已在沙箱内截到 output-max-bytes）。
 *
 * @param compileOutput 编译器输出（COMPILE_ERROR / COMPILE_TIMEOUT 时有意义）
 * @param cases         已完成的用例（可能少于请求数：整体超时或运行器被破坏时）
 * @param detail        FAILED 时的内部原因（只进日志，不给学生）
 */
public record SandboxResult(Status status, String compileOutput, List<CaseRun> cases, String detail) {

    public SandboxResult {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public enum Status {
        /** 编译成功，用例已运行（可能不完整） */
        COMPLETED,
        /** 编译失败 */
        COMPILE_ERROR,
        /** 编译超时 */
        COMPILE_TIMEOUT,
        /** 宿主侧整体墙钟超时，容器已被强制销毁 */
        TIMEOUT,
        /** 后端不可用 / 基础设施错误；未判出结果 */
        FAILED
    }

    public static SandboxResult failed(String detail) {
        return new SandboxResult(Status.FAILED, null, List.of(), detail);
    }

    /**
     * 单个用例的运行结果。
     *
     * @param exitCode       进程退出码（容器内 timeout 杀掉时为 124）
     * @param timedOut       超过每用例墙钟
     * @param stdoutExceeded stdout 超过 output-max-bytes（已截断）
     * @param stderrExceeded stderr 超过 output-max-bytes（已截断）
     */
    public record CaseRun(int index,
                          int exitCode,
                          boolean timedOut,
                          long durationMs,
                          String stdout,
                          boolean stdoutExceeded,
                          String stderr,
                          boolean stderrExceeded) {
    }
}
