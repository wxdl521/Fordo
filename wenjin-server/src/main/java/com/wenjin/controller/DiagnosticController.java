package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.config.AccessGuard;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.DiagnosticResultVO;
import com.wenjin.dto.PaperVO;
import com.wenjin.dto.SubmitRequest;
import com.wenjin.dto.SubmitResult;
import com.wenjin.service.CourseService;
import com.wenjin.service.DiagnosticResultService;
import com.wenjin.service.DiagnosticService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 入口诊断接口（学生侧，T6：组卷；T7：交卷判分）。
 */
@RestController
@RequestMapping("/api/diagnostic")
public class DiagnosticController {

    private final DiagnosticService diagnosticService;
    private final DiagnosticResultService diagnosticResultService;
    private final CourseService courseService;

    public DiagnosticController(DiagnosticService diagnosticService,
                               DiagnosticResultService diagnosticResultService,
                               CourseService courseService) {
        this.diagnosticService = diagnosticService;
        this.diagnosticResultService = diagnosticResultService;
        this.courseService = courseService;
    }

    /**
     * 获取诊断试卷（分层抽样，答案不下行）。
     * GET /api/diagnostic/paper?courseId={courseId}
     */
    @GetMapping("/paper")
    public Result<PaperVO> paper(@RequestParam("courseId") Long courseId) {
        CurrentUser.requireStudent();
        courseService.assertAccessibleByStudent(null, courseId);
        return Result.ok(diagnosticService.composePaper(courseId));
    }

    /**
     * 学生交卷判分：按 sessionToken 绑定冻结会话，精确题集匹配后判分。
     * POST /api/diagnostic/submit
     */
    @PostMapping("/submit")
    public Result<SubmitResult> submit(@RequestBody SubmitRequest req) {
        // 身份一律从 principal 派生；可选 body.studentId 仅用于 AccessGuard 自检
        long studentId = AccessGuard.requireSelfStudent(req == null ? null : req.getStudentId());
        if (req == null) {
            req = new SubmitRequest();
        }
        req.setStudentId(studentId);
        // 课程访问：优先用 body.courseId；缺省时由 service 按会话 course_id 约束
        if (req.getCourseId() != null) {
            courseService.assertAccessibleByStudent(studentId, req.getCourseId());
        }
        return Result.ok(diagnosticService.submit(req));
    }

    /**
     * 诊断回溯结果（自动选最下游薄弱点为卡点）。
     * GET /api/diagnostic/result?courseId=  （可选兼容参数 studentId）
     */
    @GetMapping("/result")
    public Result<DiagnosticResultVO> result(
            @RequestParam(value = "studentId", required = false) Long studentId,
            @RequestParam("courseId") Long courseId) {
        long id = AccessGuard.requireSelfStudent(studentId);
        courseService.assertAccessibleByStudent(id, courseId);
        return Result.ok(diagnosticResultService.getResult(id, courseId));
    }
}
