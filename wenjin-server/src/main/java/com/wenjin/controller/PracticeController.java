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
 *   <li>每个方法体第一行调用 {@link AccessGuard#assertSelf} 绑定本人身份（未登录→401，他人→403）。</li>
 *   <li>涉及课程访问的端点还调用 {@link CourseService#assertAccessibleByStudent}（发布状态 + 选课校验）。</li>
 *   <li>submit 端点的 session 归属校验由 {@link PracticeService#submit} 内部完成（service 层防御）。</li>
 * </ul>
 *
 * <h3>入参校验（T3/T4 遗留修复）</h3>
 * <ul>
 *   <li>start: {@code size ≤ 0} 时拒绝（BAD_REQUEST），0 题或负数题数会导致空会话。</li>
 *   <li>submit: 请求体 answers 中存在重复 questionId 时拒绝（BAD_REQUEST），
 *       防止同一题写入两条 answer_record 导致 EWMA 计算偏差。</li>
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
        // 1. 归属校验：studentId 必须等于当前登录用户
        AccessGuard.assertSelf(req.getStudentId());
        // 2. 入参校验：size 下界（T3 遗留：size=0/负数会组出空会话）
        if (req.getSize() != null && req.getSize() <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "size 必须为正整数（当前值：" + req.getSize() + "）");
        }
        // 3. 课程访问校验（已发布 + 已选课）
        courseService.assertAccessibleByStudent(req.getStudentId(), req.getCourseId());
        return Result.ok(practiceService.start(
                req.getStudentId(), req.getCourseId(), req.getNodeId(), req.getSize()));
    }

    /**
     * 提交练习作答：服务端判分 + 掌握度更新。
     * POST /api/practice/{sessionId}/submit
     *
     * <p>幂等：session.status=1 时 service 内部直接重建上次结果，Controller 无需额外处理。</p>
     */
    @PostMapping("/{sessionId}/submit")
    public Result<PracticeSubmitVO> submit(@PathVariable Long sessionId,
                                           @RequestBody PracticeSubmitRequest req) {
        // 1. 归属校验：studentId 必须等于当前登录用户（第一行，先鉴权后校验）
        AccessGuard.assertSelf(req.getStudentId());
        // 2. 入参校验：重复 questionId（T4 遗留：重复提交会写两条 answer_record 污染 EWMA）
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
        // 3. service 内部完成 session 归属校验（req.studentId == session.studentId，否则抛 FORBIDDEN）
        return Result.ok(practiceService.submit(sessionId, req));
    }

    /**
     * 查询近期练习历史（供前端显示"上次练习 3/5"）。
     * GET /api/practice/history?studentId=&courseId=&nodeId=
     */
    @GetMapping("/history")
    public Result<List<PracticeHistoryVO>> history(
            @RequestParam Long studentId,
            @RequestParam Long courseId,
            @RequestParam Long nodeId) {
        // 1. 归属校验
        AccessGuard.assertSelf(studentId);
        // 2. 课程访问校验
        courseService.assertAccessibleByStudent(studentId, courseId);
        return Result.ok(practiceService.getHistory(studentId, courseId, nodeId));
    }
}
