package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.AgentRunPageVO;
import com.wenjin.dto.AgentRunVO;
import com.wenjin.dto.AgentStepVO;
import com.wenjin.dto.RunStepCount;
import com.wenjin.entity.AgentRun;
import com.wenjin.entity.AgentStep;
import com.wenjin.mapper.AgentRunMapper;
import com.wenjin.mapper.AgentStepMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.service.TeacherAgentRunService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 教师端 Agent 轨迹查询：特性开关关闭 → 404；跨课程/无管理权 → 404。
 */
@Service
public class TeacherAgentRunServiceImpl implements TeacherAgentRunService {

    /** 列表每页最大条数 */
    public static final int MAX_PAGE_SIZE = 50;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private final AgentRunMapper agentRunMapper;
    private final AgentStepMapper agentStepMapper;
    private final CourseAccessService courseAccessService;
    private final FeatureFlagsProperties featureFlags;

    public TeacherAgentRunServiceImpl(AgentRunMapper agentRunMapper,
                                      AgentStepMapper agentStepMapper,
                                      CourseAccessService courseAccessService,
                                      FeatureFlagsProperties featureFlags) {
        this.agentRunMapper = agentRunMapper;
        this.agentStepMapper = agentStepMapper;
        this.courseAccessService = courseAccessService;
        this.featureFlags = featureFlags;
    }

    @Override
    public AgentRunPageVO listRuns(Long courseId, Long studentId, int page, int size) {
        assertTraceEnabled();
        if (courseId == null || courseId <= 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
        courseAccessService.assertManageCourse(courseId);

        int safePage = Math.max(1, page);
        int safeSize = clampSize(size);
        int offset = (safePage - 1) * safeSize;

        LambdaQueryWrapper<AgentRun> countQ = baseListWrapper(courseId, studentId);
        long total = agentRunMapper.selectCount(countQ);

        List<AgentRun> runs;
        if (total == 0 || offset >= total) {
            runs = Collections.emptyList();
        } else {
            LambdaQueryWrapper<AgentRun> listQ = baseListWrapper(courseId, studentId)
                    .orderByDesc(AgentRun::getCreatedAt)
                    .orderByDesc(AgentRun::getId)
                    .last("LIMIT " + offset + ", " + safeSize);
            runs = agentRunMapper.selectList(listQ);
        }

        Map<Long, Integer> stepCounts = loadStepCounts(runs);

        List<AgentRunVO> items = runs.stream()
                .map(r -> toRunVo(r, stepCounts.getOrDefault(r.getId(), 0)))
                .collect(Collectors.toList());

        AgentRunPageVO pageVo = new AgentRunPageVO();
        pageVo.setTotal(total);
        pageVo.setPage(safePage);
        pageVo.setSize(safeSize);
        pageVo.setItems(items);
        return pageVo;
    }

    @Override
    public List<AgentStepVO> listSteps(Long runId) {
        assertTraceEnabled();
        if (runId == null || runId <= 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "轨迹不存在");
        }
        AgentRun run = agentRunMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "轨迹不存在");
        }
        // 归属：run 所属课程须可管理；跨课程统一 404
        courseAccessService.assertManageCourse(run.getCourseId());

        List<AgentStep> steps = agentStepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getRunId, runId)
                .orderByAsc(AgentStep::getSeq)
                .orderByAsc(AgentStep::getId));
        if (steps == null || steps.isEmpty()) {
            return Collections.emptyList();
        }
        return steps.stream().map(this::toStepVo).collect(Collectors.toList());
    }

    private void assertTraceEnabled() {
        if (featureFlags == null || !featureFlags.isAgentTraceEnabled()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "轨迹功能未开放");
        }
    }

    public static int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static LambdaQueryWrapper<AgentRun> baseListWrapper(Long courseId, Long studentId) {
        LambdaQueryWrapper<AgentRun> q = new LambdaQueryWrapper<AgentRun>()
                .eq(AgentRun::getCourseId, courseId);
        if (studentId != null && studentId > 0) {
            q.eq(AgentRun::getStudentId, studentId);
        }
        return q;
    }

    private Map<Long, Integer> loadStepCounts(List<AgentRun> runs) {
        if (runs == null || runs.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> runIds = runs.stream().map(AgentRun::getId).collect(Collectors.toList());
        List<RunStepCount> rows = agentStepMapper.countStepsByRunIds(runIds);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Integer> map = new HashMap<>();
        for (RunStepCount row : rows) {
            if (row.getRunId() != null) {
                map.put(row.getRunId(), row.getStepCount() == null ? 0 : row.getStepCount());
            }
        }
        return map;
    }

    private AgentRunVO toRunVo(AgentRun run, int stepCount) {
        AgentRunVO vo = new AgentRunVO();
        vo.setId(run.getId());
        vo.setKind(run.getKind());
        vo.setStatus(run.getStatus());
        vo.setModel(run.getModel());
        vo.setTokensIn(run.getTokensIn());
        vo.setTokensOut(run.getTokensOut());
        vo.setDurationMs(run.getDurationMs());
        vo.setErrorCode(run.getErrorCode());
        vo.setCreatedAt(run.getCreatedAt());
        vo.setFinishedAt(run.getFinishedAt());
        vo.setStepCount(stepCount);
        return vo;
    }

    private AgentStepVO toStepVo(AgentStep step) {
        AgentStepVO vo = new AgentStepVO();
        vo.setId(step.getId());
        vo.setRunId(step.getRunId());
        vo.setSeq(step.getSeq());
        vo.setStepType(step.getStepType());
        vo.setName(step.getName());
        vo.setStatus(step.getStatus());
        vo.setInputJson(step.getInputJson());
        vo.setOutputJson(step.getOutputJson());
        vo.setTokensIn(step.getTokensIn());
        vo.setTokensOut(step.getTokensOut());
        vo.setDurationMs(step.getDurationMs());
        vo.setErrorCode(step.getErrorCode());
        vo.setCreatedAt(step.getCreatedAt());
        return vo;
    }
}
