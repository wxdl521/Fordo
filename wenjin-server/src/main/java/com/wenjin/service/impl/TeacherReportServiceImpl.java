package com.wenjin.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.AgentReportVO;
import com.wenjin.entity.AgentReport;
import com.wenjin.security.CourseAccessService;
import com.wenjin.service.TeacherReportService;
import com.wenjin.service.multiagent.AgentReportService;
import com.wenjin.support.AgentReportKind;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 教师端报告查询：toggle 关 → 404；跨课程/无管理权 → 404。
 */
@Service
public class TeacherReportServiceImpl implements TeacherReportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentReportService agentReportService;
    private final CourseAccessService courseAccessService;
    private final FeatureFlagsProperties featureFlags;

    public TeacherReportServiceImpl(AgentReportService agentReportService,
                                    CourseAccessService courseAccessService,
                                    FeatureFlagsProperties featureFlags) {
        this.agentReportService = agentReportService;
        this.courseAccessService = courseAccessService;
        this.featureFlags = featureFlags;
    }

    @Override
    public List<AgentReportVO> list(Long courseId, Integer kind) {
        assertMultiAgentEnabled();
        if (courseId == null || courseId <= 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
        courseAccessService.assertManageCourse(courseId);
        if (kind != null && !AgentReportKind.isValid(kind)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无效报告类型");
        }
        List<AgentReport> rows = agentReportService.listByCourseAndKind(courseId, kind);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        return rows.stream().map(this::toVo).collect(Collectors.toList());
    }

    @Override
    public AgentReportVO getById(Long id) {
        assertMultiAgentEnabled();
        if (id == null || id <= 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "报告不存在");
        }
        AgentReport row = agentReportService.findById(id);
        if (row == null || row.getCourseId() == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "报告不存在");
        }
        courseAccessService.assertManageCourse(row.getCourseId());
        return toVo(row);
    }

    private void assertMultiAgentEnabled() {
        if (featureFlags == null || !featureFlags.isMultiAgentEnabled()) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
    }

    private AgentReportVO toVo(AgentReport row) {
        AgentReportVO vo = new AgentReportVO();
        vo.setId(row.getId());
        vo.setCourseId(row.getCourseId());
        vo.setKind(row.getKind());
        vo.setPeriodStart(row.getPeriodStart());
        vo.setPeriodEnd(row.getPeriodEnd());
        vo.setStatus(row.getStatus());
        vo.setCreatedAt(row.getCreatedAt());
        vo.setPayload(parsePayload(row.getPayloadJson()));
        return vo;
    }

    private static JsonNode parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
