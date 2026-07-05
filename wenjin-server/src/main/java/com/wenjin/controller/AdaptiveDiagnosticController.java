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
 * <h3>鉴权约定（对齐 DiagnosticController / PracticeController）</h3>
 * <ul>
 *   <li>每个方法体第一行 {@link AccessGuard#assertSelf} 绑定本人身份（未登录→401，他人→403）。</li>
 *   <li>start 额外调 {@link CourseService#assertAccessibleByStudent}（课程存在+已发布+该生已选），
 *       对齐课程发布里程碑约定：边界调用，不侵入深层 service。</li>
 *   <li>answer/abandon 的 session 属主反查由 {@link AdaptiveDiagnosticService} 内部完成（T4 实现），
 *       Controller 不重复做。</li>
 * </ul>
 *
 * <p>Controller 保持薄：参数校验（非空） → assertSelf → （start 加 assertAccessibleByStudent）
 * → 委托 service → {@code Result.ok(vo)}。不写任何业务逻辑；错误路径全靠 service 抛的
 * BusinessException + 既有全局异常处理器映射（401/403/404/400）。</p>
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
        // 1. 归属校验：studentId 必须等于当前登录用户
        AccessGuard.assertSelf(req.getStudentId());
        // 2. 课程访问校验（已发布 + 已选课）——T4 评审移交项
        courseService.assertAccessibleByStudent(req.getStudentId(), req.getCourseId());
        return Result.ok(adaptiveDiagnosticService.start(req.getStudentId(), req.getCourseId()));
    }

    /**
     * 提交探针作答：判分 → 传播 → 选下一探针 / 终止收尾。
     * POST /api/diagnostic/adaptive/{sessionId}/answer
     */
    @PostMapping("/{sessionId}/answer")
    public Result<AdaptiveDiagnosticVO> answer(@PathVariable Long sessionId,
                                               @RequestBody AdaptiveAnswerRequest req) {
        // 1. 归属校验：studentId 必须等于当前登录用户
        AccessGuard.assertSelf(req.getStudentId());
        // 2. service 内部完成 session 属主反查（T4 实现）
        return Result.ok(adaptiveDiagnosticService.answer(sessionId, req));
    }

    /**
     * 中途放弃：status=0→2，已实测掌握度保留。
     * POST /api/diagnostic/adaptive/{sessionId}/abandon
     */
    @PostMapping("/{sessionId}/abandon")
    public Result<Void> abandon(@PathVariable Long sessionId,
                                @RequestBody AdaptiveAbandonRequest req) {
        // 1. 归属校验：studentId 必须等于当前登录用户
        AccessGuard.assertSelf(req.getStudentId());
        // 2. service 内部完成 session 属主反查（T4 实现）
        adaptiveDiagnosticService.abandon(sessionId, req.getStudentId());
        return Result.ok();
    }

    /**
     * 获取最近一次已完成会话的证据（回放归因，T8）。
     * GET /api/diagnostic/adaptive/evidence?studentId=&courseId=
     *
     * <p>守卫对齐 start 端点：assertSelf(studentId) → assertAccessibleByStudent → service。
     * 无已完成会话时 data=null（合法业务状态，非 404）。
     */
    @GetMapping("/evidence")
    public Result<EvidenceVO> evidence(@RequestParam Long studentId, @RequestParam Long courseId) {
        // 1. 归属校验：studentId 必须等于当前登录用户
        AccessGuard.assertSelf(studentId);
        // 2. 课程访问校验（已发布 + 已选课）——对齐 start 端点
        courseService.assertAccessibleByStudent(studentId, courseId);
        return Result.ok(adaptiveDiagnosticService.latestEvidence(studentId, courseId));
    }

    // ── 轻量请求体 DTO（无现成类时内联，对齐 PracticeStartRequest 风格）──

    /** start 请求体。 */
    public static class AdaptiveStartRequest {

        private Long studentId;
        private Long courseId;

        public Long getStudentId() { return studentId; }
        public void setStudentId(Long studentId) { this.studentId = studentId; }
        public Long getCourseId() { return courseId; }
        public void setCourseId(Long courseId) { this.courseId = courseId; }
    }

    /** abandon 请求体（与 answer 一致，含 studentId 供 assertSelf）。 */
    public static class AdaptiveAbandonRequest {

        private Long studentId;

        public Long getStudentId() { return studentId; }
        public void setStudentId(Long studentId) { this.studentId = studentId; }
    }
}
