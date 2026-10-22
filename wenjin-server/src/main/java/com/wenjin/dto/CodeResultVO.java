package com.wenjin.dto;

import com.wenjin.service.code.CodeExecutionService.CaseResult;
import com.wenjin.service.code.CodeExecutionService.CodeJudgeResult;
import com.wenjin.service.code.CodeExecutionService.CodeRunResult;

import java.util.List;

/**
 * SSE {@code code_result} 事件载荷：伴侣「代码运行结果」卡。
 * <p>只含沙箱返回的、已截断/已脱敏的结果；<b>不含学生源码</b>。RUN 模式下 passedCases/totalCases 为 null、cases 为空。
 *
 * @param mode   RUN | JUDGE
 * @param verdict {@link com.wenjin.service.code.CodeExecutionService.Verdict} 名称
 */
public record CodeResultVO(String mode,
                           String verdict,
                           Integer passedCases,
                           Integer totalCases,
                           String compileError,
                           String stdout,
                           String stderr,
                           List<CaseResult> cases,
                           long durationMs,
                           String message) {

    public static final String MODE_RUN = "RUN";
    public static final String MODE_JUDGE = "JUDGE";

    public CodeResultVO {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public static CodeResultVO ofRun(CodeRunResult r) {
        return new CodeResultVO(MODE_RUN, r.verdict() == null ? null : r.verdict().name(), null, null,
                r.compileError(), r.stdout(), r.stderr(), List.of(), r.durationMs(), r.message());
    }

    public static CodeResultVO ofJudge(CodeJudgeResult r) {
        return new CodeResultVO(MODE_JUDGE, r.verdict() == null ? null : r.verdict().name(),
                r.passedCases(), r.totalCases(), r.compileError(), null, null, r.cases(),
                r.durationMs(), r.message());
    }
}
