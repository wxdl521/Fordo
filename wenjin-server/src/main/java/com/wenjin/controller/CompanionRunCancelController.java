package com.wenjin.controller;

import com.wenjin.common.BusinessException;
import com.wenjin.common.Result;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AccessGuard;
import com.wenjin.entity.AgentRun;
import com.wenjin.mapper.AgentRunMapper;
import com.wenjin.service.plan.PlanCancelGuard;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/**
 * 学生取消进行中的 companion run（M10 转向入口之一）。
 */
@RestController
@RequestMapping("/api/companion/runs")
@Validated
public class CompanionRunCancelController {

    private final AgentRunMapper agentRunMapper;
    private final PlanCancelGuard cancelGuard;

    public CompanionRunCancelController(AgentRunMapper agentRunMapper, PlanCancelGuard cancelGuard) {
        this.agentRunMapper = agentRunMapper;
        this.cancelGuard = cancelGuard;
    }

    @PostMapping("/{id}/cancel")
    public Result<Map<String, Object>> cancel(@PathVariable("id") @Positive Long id) {
        long studentId = AccessGuard.requireSelfStudent(null);
        AgentRun run = agentRunMapper.selectById(id);
        if (run == null || !Objects.equals(run.getStudentId(), studentId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "run 不存在");
        }
        boolean ok = cancelGuard.requestCancel(id);
        return Result.ok(Map.of("runId", id, "cancelRequested", ok));
    }
}
