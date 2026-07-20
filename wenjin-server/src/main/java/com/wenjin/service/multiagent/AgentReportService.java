package com.wenjin.service.multiagent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.entity.AgentReport;
import com.wenjin.mapper.AgentReportMapper;
import com.wenjin.support.AgentReportStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * agent_report 持久化：{@code uk_report_period (course_id, kind, period_start)} 幂等。
 */
@Service
public class AgentReportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentReportMapper agentReportMapper;

    public AgentReportService(AgentReportMapper agentReportMapper) {
        this.agentReportMapper = Objects.requireNonNull(agentReportMapper, "agentReportMapper");
    }

    /**
     * 查找或创建 PENDING 报告行；已存在则返回既有行（幂等）。
     */
    @Transactional
    public AgentReport ensurePending(long courseId, int kind, LocalDate periodStart, LocalDate periodEnd) {
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        AgentReport existing = findByPeriod(courseId, kind, periodStart);
        if (existing != null) {
            return existing;
        }
        AgentReport row = new AgentReport();
        row.setCourseId(courseId);
        row.setKind(kind);
        row.setPeriodStart(periodStart);
        row.setPeriodEnd(periodEnd);
        row.setStatus(AgentReportStatus.PENDING);
        row.setCreatedAt(LocalDateTime.now());
        try {
            agentReportMapper.insert(row);
            return row;
        } catch (DataIntegrityViolationException e) {
            AgentReport raced = findByPeriod(courseId, kind, periodStart);
            if (raced != null) {
                return raced;
            }
            throw e;
        }
    }

    @Transactional
    public void markReady(long reportId, Object payload) {
        AgentReport row = agentReportMapper.selectById(reportId);
        if (row == null) {
            return;
        }
        row.setStatus(AgentReportStatus.READY);
        row.setPayloadJson(toJson(payload));
        agentReportMapper.updateById(row);
    }

    @Transactional
    public void markFailed(long reportId, String reason) {
        AgentReport row = agentReportMapper.selectById(reportId);
        if (row == null) {
            return;
        }
        row.setStatus(AgentReportStatus.FAILED);
        row.setPayloadJson(toJson(java.util.Map.of("error", reason == null ? "FAILED" : reason)));
        agentReportMapper.updateById(row);
    }

    public AgentReport findByPeriod(long courseId, int kind, LocalDate periodStart) {
        return agentReportMapper.selectOne(new LambdaQueryWrapper<AgentReport>()
                .eq(AgentReport::getCourseId, courseId)
                .eq(AgentReport::getKind, kind)
                .eq(AgentReport::getPeriodStart, periodStart)
                .last("LIMIT 1"));
    }

    public AgentReport findById(long id) {
        return agentReportMapper.selectById(id);
    }

    public List<AgentReport> listByCourseAndKind(long courseId, Integer kind) {
        LambdaQueryWrapper<AgentReport> q = new LambdaQueryWrapper<AgentReport>()
                .eq(AgentReport::getCourseId, courseId)
                .orderByDesc(AgentReport::getPeriodStart)
                .orderByDesc(AgentReport::getId);
        if (kind != null) {
            q.eq(AgentReport::getKind, kind);
        }
        return agentReportMapper.selectList(q);
    }

    public static String toJson(Object payload) {
        if (payload == null) {
            return null;
        }
        if (payload instanceof String s) {
            return s;
        }
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialize_failed\"}";
        }
    }
}
