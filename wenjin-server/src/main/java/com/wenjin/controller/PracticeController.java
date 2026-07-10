package com.wenjin.controller;

import com.wenjin.common.BusinessException;
import com.wenjin.common.Result;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AccessGuard;
import com.wenjin.dto.PracticeHistoryVO;
import com.wenjin.dto.PracticeStartRequest;
import com.wenjin.dto.PracticeStartVO;
import com.wenjin.dto.PracticeSubmitRequest;
import com.wenjin.dto.PracticeSubmitVO;
import com.wenjin.service.CourseService;
import com.wenjin.service.PracticeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * 节点练习接口（学生侧，M1 练习闭环 T7）。
 *
 * <h3>鉴权约定</h3>
 * <ul>
 *   <li>学生 ID 一律从 principal 派生；遗留 {@code studentId} 仅用于 mismatch → 403。</li>
 *   <li>涉及课程访问的端点还调用 {@link CourseService#assertAccessibleByStudent}。</li>
 *   <li>submit 端点的 session 归属校验由 {@link PracticeService#submit} 内部完成。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/practice")
public class PracticeController {

    private final PracticeService practiceService;
    private final CourseService courseService;

    public PracticeController(PracticeService practiceService, CourseService courseService) {
        this.practiceService = practiceService;
        this.courseService = courseService;
    }

    /**
     * 开始节点练习：组卷 + 创建 practice_session。
     * POST /api/practice/start
     */
    @PostMapping("/start")
    public Result<PracticeStartVO> start(@RequestBody PracticeStartRequest req) {
        long studentId = AccessGuard.requireSelfStudent(req.getStudentId());
        req.setStudentId(studentId);
        if (req.getSize() != null && req.getSize() <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "size 必须为正整数（当前值：" + req.getSize() + "）");
        }
        courseService.assertAccessibleByStudent(studentId, req.getCourseId());
        return Result.ok(practiceService.start(
                studentId, req.getCourseId(), req.getNodeId(),
                req.getPathItemId(), req.getSize()));
    }

    /**
     * 提交练习作答：服务端判分 + 掌握度更新。
     * POST /api/practice/{sessionId}/submit
     */
    @PostMapping("/{sessionId}/submit")
    public Result<PracticeSubmitVO> submit(@PathVariable Long sessionId,
                                           @RequestBody PracticeSubmitRequest req) {
        long studentId = AccessGuard.requireSelfStudent(req.getStudentId());
        req.setStudentId(studentId);
        if (req.getAnswers() != null) {
            long total = req.getAnswers().stream()
                    .map(PracticeSubmitRequest.AnswerItem::getQuestionId)
                    .filter(Objects::nonNull)
                    .count();
            long distinct = req.getAnswers().stream()
                    .map(PracticeSubmitRequest.AnswerItem::getQuestionId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .count();
            if (distinct < total) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "作答列表中存在重复的 questionId，请去重后重试");
            }
        }
        return Result.ok(practiceService.submit(sessionId, req));
    }

    /**
     * 查询近期练习历史（供前端显示"上次练习 3/5"）。
     * GET /api/practice/history?courseId=&nodeId=  （可选兼容参数 studentId）
     */
    @GetMapping("/history")
    public Result<List<PracticeHistoryVO>> history(
            @RequestParam(value = "studentId", required = false) Long studentId,
            @RequestParam Long courseId,
            @RequestParam Long nodeId) {
        long id = AccessGuard.requireSelfStudent(studentId);
        courseService.assertAccessibleByStudent(id, courseId);
        return Result.ok(practiceService.getHistory(id, courseId, nodeId));
    }
}
