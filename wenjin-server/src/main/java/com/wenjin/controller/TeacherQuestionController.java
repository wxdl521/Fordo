package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.QuestionReviewRequest;
import com.wenjin.dto.TeacherQuestionPageVO;
import com.wenjin.service.TeacherQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 教师端 - 题目审核池控制器。
 * 教师可筛选待审核题目、查看详情、批量通过/驳回。
 */
@RestController
@RequestMapping("/api/teacher/questions")
@RequiredArgsConstructor
public class TeacherQuestionController {

    private final TeacherQuestionService teacherQuestionService;

    /**
     * 分页查询题目审核池（支持状态/知识点/置信度筛选）。
     *
     * @param courseId 课程 ID
     * @param status   审核状态（0=待审核, 1=已通过, 2=已驳回，null=全部）
     * @param nodeCode 知识点过滤（仅显示该知识点为主考点的题目，null=不限）
     * @param conf     置信度区间（ge85 / mid / lt70，null=不限）
     * @param page     页码（从 1 开始）
     * @param size     每页条数
     * @return 分页结果（含题目列表 + 各状态统计）
     */
    @GetMapping
    public Result<TeacherQuestionPageVO> list(
            @RequestParam Long courseId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String nodeCode,
            @RequestParam(required = false) String conf,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        TeacherQuestionPageVO result = teacherQuestionService.list(courseId, status, nodeCode, conf, page, size);
        return Result.ok(result);
    }

    /**
     * 批量审核题目（通过/驳回）。
     *
     * @param courseId 课程 ID
     * @param req      审核请求（ids + action: pass/reject）
     * @return 受影响行数
     */
    @PostMapping("/review")
    public Result<Integer> review(
            @RequestParam Long courseId,
            @RequestBody QuestionReviewRequest req
    ) {
        int affected = teacherQuestionService.review(courseId, req);
        return Result.ok(affected);
    }
}
