package com.wenjin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wenjin.service.code.CodeExecutionService.CaseResult;
import com.wenjin.service.code.CodeExecutionService.CodeJudgeResult;
import lombok.Data;

import java.util.List;

/**
 * 编程题判分结果的学生可见形态（练习提交 {@code graded[].codeResult}）。
 *
 * <p>hidden 用例在此处被再次脱敏：无论沙箱实现是否填充，hidden 用例的
 * stdin / expected / actual 预览一律置空，只保留 passed / verdict / 耗时。
 */
@Data
public class CodeJudgeVO {

    /** 归并后的总裁决（ACCEPTED / WRONG_ANSWER / COMPILE_ERROR / RUNTIME_ERROR / TIMEOUT / RESOURCE_LIMIT / REJECTED / RATE_LIMITED / UNAVAILABLE） */
    private String verdict;

    private int passedCases;

    private int totalCases;

    /** 编译失败时的编译器输出（已被沙箱截断） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String compileError;

    private List<CaseVO> cases;

    private long durationMs;

    /** 面向学生的一句话说明 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String message;

    @Data
    public static class CaseVO {
        /** 用例序号（沙箱给出的 index） */
        private int index;
        private boolean passed;
        private boolean hidden;
        private String verdict;
        /** 非 hidden 用例的输入预览；hidden 恒为 null */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String stdinPreview;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String expectedPreview;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String actualPreview;
        private long durationMs;
    }

    public static CodeJudgeVO from(CodeJudgeResult r) {
        CodeJudgeVO vo = new CodeJudgeVO();
        vo.setVerdict(r.verdict() == null ? null : r.verdict().name());
        vo.setPassedCases(r.passedCases());
        vo.setTotalCases(r.totalCases());
        vo.setCompileError(r.compileError());
        vo.setDurationMs(r.durationMs());
        vo.setMessage(r.message());
        vo.setCases(r.cases().stream().map(CodeJudgeVO::caseOf).toList());
        return vo;
    }

    private static CaseVO caseOf(CaseResult c) {
        CaseVO vo = new CaseVO();
        vo.setIndex(c.index());
        vo.setPassed(c.passed());
        vo.setHidden(c.hidden());
        vo.setVerdict(c.verdict() == null ? null : c.verdict().name());
        vo.setDurationMs(c.durationMs());
        if (!c.hidden()) {
            vo.setStdinPreview(c.stdinPreview());
            vo.setExpectedPreview(c.expectedPreview());
            vo.setActualPreview(c.actualPreview());
        }
        return vo;
    }
}
