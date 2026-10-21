package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.AuditLogVO;
import com.wenjin.dto.AdminPageVO;
import com.wenjin.dto.LoginLogVO;
import com.wenjin.dto.MonitorRows;
import com.wenjin.dto.OverviewVO;
import com.wenjin.entity.AuditLog;
import com.wenjin.entity.LoginLog;
import com.wenjin.mapper.AuditLogMapper;
import com.wenjin.mapper.LoginLogMapper;
import com.wenjin.mapper.MonitorMapper;
import com.wenjin.service.impl.MonitorServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MonitorServiceImpl：日志分页查询（筛选/分页上限/校验）+ 平台总览（日期补齐、各计数映射、窗口起点）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitorServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 固定"今天"= 2026-10-21。 */
    private static final Clock CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 10, 21, 15, 30, 0).atZone(ZONE).toInstant(), ZONE);

    @Mock
    private AuditLogMapper auditLogMapper;
    @Mock
    private LoginLogMapper loginLogMapper;
    @Mock
    private MonitorMapper monitorMapper;

    private MonitorServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MonitorServiceImpl(auditLogMapper, loginLogMapper, monitorMapper, CLOCK);
    }

    // ───────────────────────── 操作日志 ─────────────────────────

    private static AuditLog auditRow(long id) {
        AuditLog row = new AuditLog();
        row.setId(id);
        row.setOperatorId(1L);
        row.setOperatorName("root");
        row.setOperatorRole(3);
        row.setAction("USER_CREATE");
        row.setTargetType("USER");
        row.setTargetId("9");
        row.setHttpMethod("POST");
        row.setPath("/api/system-admin/users");
        row.setHttpStatus(200);
        row.setSuccess(1);
        row.setIp("10.0.0.7");
        row.setRequestId("req-1");
        row.setCreatedAt(LocalDateTime.of(2026, 10, 21, 10, 15, 30));
        return row;
    }

    @Test
    @DisplayName("操作日志：size 超上限钳到 100、page<1 取 1；按时间倒序 LIMIT 0,100；VO 字段映射")
    void auditLogs_clampsAndMaps() {
        when(auditLogMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(auditLogMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(auditRow(5L)));

        AdminPageVO<AuditLogVO> page = service.listAuditLogs(null, null, null, null, null, 0, 500);

        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(100);
        AuditLogVO vo = page.getRecords().get(0);
        assertThat(vo.getId()).isEqualTo(5L);
        assertThat(vo.getOperatorId()).isEqualTo(1L);
        assertThat(vo.getOperatorName()).isEqualTo("root");
        assertThat(vo.getOperatorRole()).isEqualTo(3);
        assertThat(vo.getAction()).isEqualTo("USER_CREATE");
        assertThat(vo.getTargetType()).isEqualTo("USER");
        assertThat(vo.getTargetId()).isEqualTo("9");
        assertThat(vo.getHttpMethod()).isEqualTo("POST");
        assertThat(vo.getPath()).isEqualTo("/api/system-admin/users");
        assertThat(vo.getHttpStatus()).isEqualTo(200);
        assertThat(vo.isSuccess()).isTrue();
        assertThat(vo.getIp()).isEqualTo("10.0.0.7");
        assertThat(vo.getRequestId()).isEqualTo("req-1");
        assertThat(vo.getCreatedAt()).isEqualTo("2026-10-21T10:15:30");

        ArgumentCaptor<QueryWrapper<AuditLog>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(auditLogMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .contains("ORDER BY created_at DESC,id DESC")
                .contains("LIMIT 0,100");
    }

    @Test
    @DisplayName("操作日志：success=0 映射为 false；默认 page=1,size=20；第 3 页偏移 40")
    void auditLogs_defaultsOffsetAndFailureFlag() {
        AuditLog failed = auditRow(6L);
        failed.setSuccess(0);
        failed.setHttpStatus(403);
        when(auditLogMapper.selectCount(any(QueryWrapper.class))).thenReturn(100L);
        when(auditLogMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(failed));

        AdminPageVO<AuditLogVO> defaults = service.listAuditLogs(null, null, null, null, null, null, null);
        AdminPageVO<AuditLogVO> third = service.listAuditLogs(null, null, null, null, null, 3, 20);

        assertThat(defaults.getPage()).isEqualTo(1);
        assertThat(defaults.getSize()).isEqualTo(20);
        assertThat(defaults.getRecords().get(0).isSuccess()).isFalse();
        assertThat(third.getPage()).isEqualTo(3);
        ArgumentCaptor<QueryWrapper<AuditLog>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(auditLogMapper, times(2)).selectList(captor.capture());
        assertThat(captor.getAllValues().get(0).getSqlSegment()).contains("LIMIT 0,20");
        assertThat(captor.getAllValues().get(1).getSqlSegment()).contains("LIMIT 40,20");
    }

    @Test
    @DisplayName("操作日志：筛选条件进入 SQL——操作人模糊(转义通配符)、动作、结果、日期含当日")
    void auditLogs_filtersApplied() {
        when(auditLogMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        service.listAuditLogs("  a_b%c ", " USER_CREATE ", 0,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), 1, 20);

        ArgumentCaptor<QueryWrapper<AuditLog>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(auditLogMapper).selectCount(captor.capture());
        QueryWrapper<AuditLog> w = captor.getValue();
        assertThat(w.getSqlSegment())
                .contains("operator_name").contains("action").contains("success").contains("created_at");
        assertThat(w.getParamNameValuePairs().values())
                .contains("%a\\_b\\%c%")
                .contains("USER_CREATE")
                .contains(0)
                // 起始日 00:00（含）到结束日次日 00:00（不含）= 含结束日整天
                .contains(LocalDateTime.of(2026, 10, 1, 0, 0))
                .contains(LocalDateTime.of(2026, 10, 8, 0, 0));
    }

    @Test
    @DisplayName("操作日志：总数为 0 时不再查记录")
    void auditLogs_zeroTotal_skipsRecordQuery() {
        when(auditLogMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        AdminPageVO<AuditLogVO> page = service.listAuditLogs("nobody", null, null, null, null, 1, 20);

        assertThat(page.getTotal()).isZero();
        assertThat(page.getRecords()).isEmpty();
        verify(auditLogMapper, never()).selectList(any(QueryWrapper.class));
    }

    @Test
    @DisplayName("操作日志：success 不是 0/1 → 400；起始日晚于结束日 → 400；都不会查库")
    void auditLogs_invalidParams_rejected() {
        assertBadRequest(() -> service.listAuditLogs(null, null, 2, null, null, 1, 20));
        assertBadRequest(() -> service.listAuditLogs(null, null, null,
                LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 1), 1, 20));
        verify(auditLogMapper, never()).selectCount(any(QueryWrapper.class));
    }

    @Test
    @DisplayName("动作下拉：原样返回库里去重后的 action")
    void auditActions_passThrough() {
        when(auditLogMapper.selectDistinctActions()).thenReturn(List.of("COURSE_CREATE", "USER_CREATE"));

        assertThat(service.listAuditActions()).containsExactly("COURSE_CREATE", "USER_CREATE");
    }

    // ───────────────────────── 登录日志 ─────────────────────────

    @Test
    @DisplayName("登录日志：字段映射（失败原因、UA、user_id 为空）+ 分页上限 + 倒序")
    void loginLogs_mapsAndClamps() {
        LoginLog row = new LoginLog();
        row.setId(11L);
        row.setUserId(null);
        row.setUsername("ghost");
        row.setSuccess(0);
        row.setFailReason("BAD_CREDENTIALS");
        row.setIp("10.0.0.9");
        row.setUserAgent("Mozilla/5.0");
        row.setCreatedAt(LocalDateTime.of(2026, 10, 21, 9, 0, 1));
        when(loginLogMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(loginLogMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(row));

        AdminPageVO<LoginLogVO> page = service.listLoginLogs(null, null, null, null, 1, 999);

        assertThat(page.getSize()).isEqualTo(100);
        LoginLogVO vo = page.getRecords().get(0);
        assertThat(vo.getId()).isEqualTo(11L);
        assertThat(vo.getUserId()).isNull();
        assertThat(vo.getUsername()).isEqualTo("ghost");
        assertThat(vo.isSuccess()).isFalse();
        assertThat(vo.getFailReason()).isEqualTo("BAD_CREDENTIALS");
        assertThat(vo.getIp()).isEqualTo("10.0.0.9");
        assertThat(vo.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(vo.getCreatedAt()).isEqualTo("2026-10-21T09:00:01");
        ArgumentCaptor<QueryWrapper<LoginLog>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(loginLogMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .contains("ORDER BY created_at DESC,id DESC").contains("LIMIT 0,100");
    }

    @Test
    @DisplayName("登录日志：用户名模糊(转义)、结果、日期筛选；非法参数 400")
    void loginLogs_filtersAndValidation() {
        when(loginLogMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        service.listLoginLogs(" al_ice ", 1, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20), 1, 20);

        ArgumentCaptor<QueryWrapper<LoginLog>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(loginLogMapper).selectCount(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("username").contains("success").contains("created_at");
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .contains("%al\\_ice%")
                .contains(1)
                .contains(LocalDateTime.of(2026, 10, 20, 0, 0))
                .contains(LocalDateTime.of(2026, 10, 21, 0, 0));

        assertBadRequest(() -> service.listLoginLogs(null, 5, null, null, 1, 20));
        assertBadRequest(() -> service.listLoginLogs(null, null,
                LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 1), 1, 20));
    }

    // ───────────────────────── 平台总览 ─────────────────────────

    private static MonitorRows.LoginDay loginDay(String day, long logins, long active, long failures) {
        MonitorRows.LoginDay r = new MonitorRows.LoginDay();
        r.setDay(day);
        r.setLogins(logins);
        r.setActiveUsers(active);
        r.setFailures(failures);
        return r;
    }

    private static MonitorRows.AiDay aiDay(String day, long runs, long tokens) {
        MonitorRows.AiDay r = new MonitorRows.AiDay();
        r.setDay(day);
        r.setRuns(runs);
        r.setTokens(tokens);
        return r;
    }

    private static MonitorRows.NameStat stat(String name, long runs, long tokens) {
        MonitorRows.NameStat r = new MonitorRows.NameStat();
        r.setName(name);
        r.setRuns(runs);
        r.setTokens(tokens);
        return r;
    }

    private static MonitorRows.NameCount nameCount(String name, long cnt) {
        MonitorRows.NameCount r = new MonitorRows.NameCount();
        r.setName(name);
        r.setCnt(cnt);
        return r;
    }

    @Test
    @DisplayName("总览：计数映射——用户、课程、AI 汇总、作业")
    void overview_mapsCounts() {
        MonitorRows.UserCounts users = new MonitorRows.UserCounts();
        users.setTotal(10L);
        users.setTeachers(2L);
        users.setStudents(7L);
        users.setAdmins(1L);
        users.setDisabled(3L);
        when(monitorMapper.countUsers()).thenReturn(users);
        MonitorRows.CourseCounts courses = new MonitorRows.CourseCounts();
        courses.setTotal(6L);
        courses.setPublished(3L);
        courses.setDraft(2L);
        courses.setArchived(1L);
        when(monitorMapper.countCourses()).thenReturn(courses);
        MonitorRows.AiTotals ai = new MonitorRows.AiTotals();
        ai.setRuns(40L);
        ai.setFailedRuns(4L);
        ai.setTokensIn(1000L);
        ai.setTokensOut(2500L);
        ai.setAvgDurationMs(820L);
        when(monitorMapper.aiTotals(any())).thenReturn(ai);
        when(monitorMapper.aiByKind(any())).thenReturn(List.of(stat("COMPANION_CHAT", 30, 3000), stat("DIAGNOSTIC", 10, 500)));
        when(monitorMapper.aiByModel(any())).thenReturn(List.of(stat("deepseek-v4-pro", 38, 3400), stat("unknown", 2, 100)));
        MonitorRows.JobTotals jobs = new MonitorRows.JobTotals();
        jobs.setTotal(9L);
        jobs.setSucceeded(6L);
        jobs.setFailed(2L);
        when(monitorMapper.jobTotals(any())).thenReturn(jobs);
        when(monitorMapper.jobsByKind(any())).thenReturn(List.of(nameCount("GRAPH_EXTRACT", 5), nameCount("QUESTION_GENERATE", 4)));

        OverviewVO vo = service.overview(7);

        assertThat(vo.getUsers().getTotal()).isEqualTo(10);
        assertThat(vo.getUsers().getTeachers()).isEqualTo(2);
        assertThat(vo.getUsers().getStudents()).isEqualTo(7);
        assertThat(vo.getUsers().getAdmins()).isEqualTo(1);
        assertThat(vo.getUsers().getDisabled()).isEqualTo(3);
        assertThat(vo.getCourses().getTotal()).isEqualTo(6);
        assertThat(vo.getCourses().getPublished()).isEqualTo(3);
        assertThat(vo.getCourses().getDraft()).isEqualTo(2);
        assertThat(vo.getCourses().getArchived()).isEqualTo(1);
        assertThat(vo.getAi().getRuns()).isEqualTo(40);
        assertThat(vo.getAi().getFailedRuns()).isEqualTo(4);
        assertThat(vo.getAi().getTokensIn()).isEqualTo(1000);
        assertThat(vo.getAi().getTokensOut()).isEqualTo(2500);
        assertThat(vo.getAi().getAvgDurationMs()).isEqualTo(820);
        assertThat(vo.getAi().getByKind()).extracting(OverviewVO.AiKindStat::getKind, OverviewVO.AiKindStat::getRuns,
                OverviewVO.AiKindStat::getTokens)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("COMPANION_CHAT", 30L, 3000L),
                        org.assertj.core.groups.Tuple.tuple("DIAGNOSTIC", 10L, 500L));
        assertThat(vo.getAi().getByModel()).extracting(OverviewVO.AiModelStat::getModel, OverviewVO.AiModelStat::getRuns,
                OverviewVO.AiModelStat::getTokens)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("deepseek-v4-pro", 38L, 3400L),
                        org.assertj.core.groups.Tuple.tuple("unknown", 2L, 100L));
        assertThat(vo.getAi().getJobs().getTotal()).isEqualTo(9);
        assertThat(vo.getAi().getJobs().getSucceeded()).isEqualTo(6);
        assertThat(vo.getAi().getJobs().getFailed()).isEqualTo(2);
        assertThat(vo.getAi().getJobs().getByKind()).extracting(OverviewVO.JobKindStat::getKind,
                OverviewVO.JobKindStat::getCount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("GRAPH_EXTRACT", 5L),
                        org.assertj.core.groups.Tuple.tuple("QUESTION_GENERATE", 4L));
    }

    @Test
    @DisplayName("总览：daily 补齐无数据日期为 0、按日期升序、长度=days；今日指标取最后一天")
    void overview_dailyFilledAscending_andTodayMetrics() {
        // days=3 → 10-19 / 10-20 / 10-21；库里只有 10-21 和 10-19（故意乱序），10-20 要补 0
        when(monitorMapper.loginDaily(any())).thenReturn(List.of(
                loginDay("2026-10-21", 5, 3, 2),
                loginDay("2026-10-19", 1, 1, 0)));
        when(monitorMapper.aiDaily(any())).thenReturn(List.of(aiDay("2026-10-20", 4, 900)));

        OverviewVO vo = service.overview(3);

        assertThat(vo.getActivity().getDaily()).hasSize(3);
        assertThat(vo.getActivity().getDaily()).extracting(OverviewVO.ActivityDay::getDate)
                .containsExactly("2026-10-19", "2026-10-20", "2026-10-21");
        assertThat(vo.getActivity().getDaily()).extracting(OverviewVO.ActivityDay::getLogins)
                .containsExactly(1L, 0L, 5L);
        assertThat(vo.getActivity().getDaily()).extracting(OverviewVO.ActivityDay::getActiveUsers)
                .containsExactly(1L, 0L, 3L);
        assertThat(vo.getActivity().getDaily()).extracting(OverviewVO.ActivityDay::getFailures)
                .containsExactly(0L, 0L, 2L);
        assertThat(vo.getActivity().getTodayActiveUsers()).isEqualTo(3);
        assertThat(vo.getActivity().getTodayLoginFailures()).isEqualTo(2);

        assertThat(vo.getAi().getDaily()).extracting(OverviewVO.AiDay::getDate)
                .containsExactly("2026-10-19", "2026-10-20", "2026-10-21");
        assertThat(vo.getAi().getDaily()).extracting(OverviewVO.AiDay::getRuns).containsExactly(0L, 4L, 0L);
        assertThat(vo.getAi().getDaily()).extracting(OverviewVO.AiDay::getTokens).containsExactly(0L, 900L, 0L);
    }

    @Test
    @DisplayName("总览：窗口起点 = 今天-(days-1) 的 00:00（注入的 Clock），所有时间窗口查询用同一起点")
    void overview_windowStartUsesClock() {
        service.overview(7);

        LocalDateTime expected = LocalDateTime.of(2026, 10, 15, 0, 0);
        verify(monitorMapper).loginDaily(expected);
        verify(monitorMapper).aiTotals(expected);
        verify(monitorMapper).aiDaily(expected);
        verify(monitorMapper).aiByKind(expected);
        verify(monitorMapper).aiByModel(expected);
        verify(monitorMapper).jobTotals(expected);
        verify(monitorMapper).jobsByKind(expected);
    }

    @Test
    @DisplayName("总览：days 默认 7、下限 1、上限 30")
    void overview_daysDefaultAndClamp() {
        assertThat(service.overview(null).getActivity().getDaily()).hasSize(7);
        assertThat(service.overview(0).getActivity().getDaily()).hasSize(1);
        assertThat(service.overview(-5).getActivity().getDaily()).hasSize(1);
        OverviewVO capped = service.overview(500);
        assertThat(capped.getActivity().getDaily()).hasSize(30);
        assertThat(capped.getAi().getDaily()).hasSize(30);
        assertThat(capped.getActivity().getDaily().get(0).getDate()).isEqualTo("2026-09-22");
        assertThat(capped.getActivity().getDaily().get(29).getDate()).isEqualTo("2026-10-21");
    }

    @Test
    @DisplayName("总览：全库为空（聚合行为 null / 空列表）时所有数字为 0、数组为空而非 null")
    void overview_emptyData_zeroFilled() {
        OverviewVO vo = service.overview(2);

        assertThat(vo.getUsers().getTotal()).isZero();
        assertThat(vo.getCourses().getTotal()).isZero();
        assertThat(vo.getActivity().getTodayActiveUsers()).isZero();
        assertThat(vo.getActivity().getTodayLoginFailures()).isZero();
        assertThat(vo.getActivity().getDaily()).hasSize(2)
                .allSatisfy(d -> assertThat(d.getLogins() + d.getActiveUsers() + d.getFailures()).isZero());
        assertThat(vo.getAi().getRuns()).isZero();
        assertThat(vo.getAi().getAvgDurationMs()).isZero();
        assertThat(vo.getAi().getByKind()).isNotNull().isEmpty();
        assertThat(vo.getAi().getByModel()).isNotNull().isEmpty();
        assertThat(vo.getAi().getJobs().getTotal()).isZero();
        assertThat(vo.getAi().getJobs().getByKind()).isNotNull().isEmpty();
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ResultCode.BAD_REQUEST.getCode()));
    }
}
