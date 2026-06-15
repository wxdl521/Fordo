package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.PaperVO;
import com.wenjin.service.DiagnosticService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 入口诊断接口（学生侧，T6：组卷；T7：提交答题将追加于此）。
 */
@RestController
@RequestMapping("/api/diagnostic")
public class DiagnosticController {

    private final DiagnosticService diagnosticService;

    public DiagnosticController(DiagnosticService diagnosticService) {
        this.diagnosticService = diagnosticService;
    }

    /**
     * 获取诊断试卷（分层抽样，答案不下行）。
     * GET /api/diagnostic/paper?courseId={courseId}
     */
    @GetMapping("/paper")
    public Result<PaperVO> paper(@RequestParam("courseId") Long courseId) {
        return Result.ok(diagnosticService.composePaper(courseId));
    }
}
