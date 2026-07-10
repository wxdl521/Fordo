package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.config.AccessGuard;
import com.wenjin.dto.AdaptiveAnswerRequest;
import com.wenjin.dto.AdaptiveDiagnosticVO;
import com.wenjin.dto.EvidenceVO;
import com.wenjin.service.AdaptiveDiagnosticService;
import com.wenjin.service.CourseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图引导自适应诊断接口（学生侧，M2 T6）。
 *
 * <h3>鉴权约定</h3>
 * <ul>
 *   <li>每个方法通过 {@link AccessGuard#requireSelfStudent} 从 principal 派生学生 ID；
 *       遗留 body/query {@code studentId} 若存在且 ≠ principal → 403。</li>
 *   <li>start / evidence 额外调 {@link CourseService#assertAccessibleByStudent}。</li>
 *   <li>answer/abandon 的 session 属主反查由 service 内部完成。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/diagnostic/adaptive")
public class AdaptiveDiagnosticController {

    private final AdaptiveDiagnosticService adaptiveDiagnosticService;
    private final CourseService courseService;

    public AdaptiveDiagnosticController(AdaptiveDiagnosticService adaptiveDiagnosticService,
                                        CourseService courseService) {
        this.adaptiveDiagnosticService = adaptiveDiagnosticService;
        this.courseService = courseService;
    }

    /**
     * 开始自适应诊断：建会话、组首探针。
     * POST /api/diagnostic/adaptive/start
     */
    @PostMapping("/start")
    public Result<AdaptiveDiagnosticVO> start(@RequestBody AdaptiveStartRequest req) {
        long studentId = AccessGuard.requireSelfStudent(req.getStudentId());
        courseService.assertAccessibleByStudent(studentId, req.getCourseId());
        return Result.ok(adaptiveDiagnosticService.start(studentId, req.getCourseId()));
    }

    /**
     * 提交探针作答：判分 → 传播 → 选下一探针 / 终止收尾。
     * POST /api/diagnostic/adaptive/{sessionId}/answer
     */
    @PostMapping("/{sessionId}/answer")
    public Result<AdaptiveDiagnosticVO> answer(@PathVariable Long sessionId,
                                               @RequestBody AdaptiveAnswerRequest req) {
        long studentId = AccessGuard.requireSelfStudent(req.getStudentId());
        req.setStudentId(studentId);
        return Result.ok(adaptiveDiagnosticService.answer(sessionId, req));
    }

    /**
     * 中途放弃：status=0→2，已实测掌握度保留。
     * POST /api/diagnostic/adaptive/{sessionId}/abandon
     */
    @PostMapping("/{sessionId}/abandon")
    public Result<Void> abandon(@PathVariable Long sessionId,
                                @RequestBody(required = false) AdaptiveAbandonRequest req) {
        Long clientId = req == null ? null : req.getStudentId();
        long studentId = AccessGuard.requireSelfStudent(clientId);
        adaptiveDiagnosticService.abandon(sessionId, studentId);
        return Result.ok();
    }

    /**
     * 获取最近一次已完成会话的证据（回放归因，T8）。
     * GET /api/diagnostic/adaptive/evidence?courseId=  （可选兼容参数 studentId）
     */
    @GetMapping("/evidence")
    public Result<EvidenceVO> evidence(
            @RequestParam(value = "studentId", required = false) Long studentId,
            @RequestParam Long courseId) {
        long id = AccessGuard.requireSelfStudent(studentId);
        courseService.assertAccessibleByStudent(id, courseId);
        return Result.ok(adaptiveDiagnosticService.latestEvidence(id, courseId));
    }

    // ── 轻量请求体 DTO ──

    /** start 请求体。studentId 为可选兼容字段。 */
    public static class AdaptiveStartRequest {

        private Long studentId;
        private Long courseId;

        public Long getStudentId() { return studentId; }
        public void setStudentId(Long studentId) { this.studentId = studentId; }
        public Long getCourseId() { return courseId; }
        public void setCourseId(Long courseId) { this.courseId = courseId; }
    }

    /** abandon 请求体（studentId 可选兼容字段）。 */
    public static class AdaptiveAbandonRequest {

        private Long studentId;

        public Long getStudentId() { return studentId; }
        public void setStudentId(Long studentId) { this.studentId = studentId; }
    }
}
