package com.wenjin.service.multiagent;

import com.wenjin.entity.AgentReport;
import com.wenjin.support.AgentReportKind;
import com.wenjin.support.AgentReportStatus;
import com.wenjin.support.multiagent.ReportAggregations;
import com.wenjin.support.multiagent.ReportAggregations.Anomaly;
import com.wenjin.support.multiagent.ReportAggregations.EffectiveDelta;
import com.wenjin.support.multiagent.ReportAggregations.Engagement;
import com.wenjin.support.multiagent.ReportAggregations.NodeStudentEffective;
import com.wenjin.support.multiagent.ReportAggregations.StudentLastPractice;
import com.wenjin.support.multiagent.ReportAggregations.WeakNode;
import com.wenjin.support.multiagent.ReportNarrativeGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 教师周报：确定性聚合 + 可选叙述；narrative 失败仍 READY。
 * <p>数据收集由调用方注入（薄骨架：job 路径可用空夹具，生产可接查询端口）。
 */
@Service
public class TeacherWeeklyReportService {

    private static final Logger log = LoggerFactory.getLogger(TeacherWeeklyReportService.class);

    private final AgentReportService agentReportService;
    private final ReportNarrativeGenerator narrativeGenerator;

    public TeacherWeeklyReportService(AgentReportService agentReportService,
                                      @Autowired(required = false) ReportNarrativeGenerator narrativeGenerator) {
        this.agentReportService = Objects.requireNonNull(agentReportService, "agentReportService");
        this.narrativeGenerator = narrativeGenerator != null
                ? narrativeGenerator
                : new ReportNarrativeGenerator.NoOp();
    }

    /**
     * 构建并落库周报。已 READY 的同周期报告直接返回（幂等）。
     *
     * @return report id
     */
    public long buildAndStore(
            long courseId,
            LocalDate periodStart,
            LocalDate periodEnd,
            List<NodeStudentEffective> masteryRows,
            List<EffectiveDelta> deltas,
            List<StudentLastPractice> practices,
            int dialogueThisWeek,
            int dialogueLastWeek,
            int practiceThisWeek,
            int practiceLastWeek
    ) {
        AgentReport report = agentReportService.ensurePending(
                courseId, AgentReportKind.TEACHER_WEEKLY, periodStart, periodEnd);
        if (report.getStatus() != null && report.getStatus() == AgentReportStatus.READY
                && report.getPayloadJson() != null && !report.getPayloadJson().isBlank()) {
            return report.getId();
        }

        List<WeakNode> weak = ReportAggregations.weakNodes(
                masteryRows, ReportAggregations.DEFAULT_WEAK_TOP_N);
        List<Anomaly> anomalies = ReportAggregations.anomalies(deltas, practices, periodEnd);
        Engagement engagement = ReportAggregations.engagement(
                dialogueThisWeek, dialogueLastWeek, practiceThisWeek, practiceLastWeek);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("weakNodes", ReportAggregations.weakNodesAsMaps(weak));
        payload.put("anomalies", ReportAggregations.anomaliesAsMaps(anomalies));
        payload.put("engagement", ReportAggregations.engagementAsMap(engagement));

        String narrative = "";
        try {
            String summary = "weak=" + weak.size()
                    + " anomalies=" + anomalies.size()
                    + " dialogueWow=" + engagement.dialogueWowRatio();
            String generated = narrativeGenerator.generate(summary);
            narrative = generated == null ? "" : generated;
        } catch (Exception e) {
            log.warn("weekly report narrative failed courseId={}: {}", courseId, e.toString());
            narrative = "";
        }
        payload.put("narrative", narrative);

        agentReportService.markReady(report.getId(), payload);
        return report.getId();
    }

    /**
     * Job 入口：无上游数据时用空聚合（骨架仍产出 READY 报告）。
     */
    public long buildEmptySkeleton(long courseId, LocalDate periodStart, LocalDate periodEnd) {
        return buildAndStore(courseId, periodStart, periodEnd,
                List.of(), List.of(), List.of(), 0, 0, 0, 0);
    }
}
