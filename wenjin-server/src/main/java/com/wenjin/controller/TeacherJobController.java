package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.AiJobVO;
import com.wenjin.service.AiJobService;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教师端 AI 作业查询与取消。
 * <p>派发仍走 /api/admin/** 长写入口（202）；进度与取消走本控制器。
 */
@RestController
@RequestMapping("/api/teacher/jobs")
@Validated
public class TeacherJobController {

    private final AiJobService aiJobService;

    public TeacherJobController(AiJobService aiJobService) {
        this.aiJobService = aiJobService;
    }

    /** GET /api/teacher/jobs/{id} — 发起人可读；跨教师 404 */
    @GetMapping("/{id}")
    public Result<AiJobVO> get(@PathVariable("id") @Positive Long id) {
        return Result.ok(aiJobService.getForOwner(id));
    }

    /**
     * POST /api/teacher/jobs/{id}/cancel
     * PENDING 立即取消；RUNNING 登记协作取消标志。
     */
    @PostMapping("/{id}/cancel")
    public Result<AiJobVO> cancel(@PathVariable("id") @Positive Long id) {
        return Result.ok(aiJobService.cancel(id));
    }
}
