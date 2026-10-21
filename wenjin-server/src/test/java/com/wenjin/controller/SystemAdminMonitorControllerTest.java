package com.wenjin.controller;

import com.wenjin.common.BusinessException;
import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AdminAuthInterceptor;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.AuditLogVO;
import com.wenjin.dto.LogPageVO;
import com.wenjin.dto.LoginLogVO;
import com.wenjin.dto.OverviewVO;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.MonitorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SystemAdminMonitorController：契约（路径/参数/返回字段）+ 管理员路由鉴权。
 */
@ExtendWith(MockitoExtension.class)
class SystemAdminMonitorControllerTest {

    private static final String BASE = "/api/system-admin";

    @Mock
    private MonitorService monitorService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SystemAdminMonitorController(monitorService))
                .addInterceptors(new AdminAuthInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        CurrentUser.set(new CurrentPrincipal(1L, UserRole.ADMIN));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    // ───────────────────────── GET /audit-logs ─────────────────────────

    @Test
    @DisplayName("GET /audit-logs：参数透传（日期 yyyy-MM-dd），返回 {records,total,page,size} 与 AuditLogVO 全部字段")
    void auditLogs_passesParamsAndReturnsContract() throws Exception {
        AuditLogVO row = new AuditLogVO();
        row.setId(5L);
        row.setOperatorId(1L);
        row.setOperatorName("root");
        row.setOperatorRole(3);
        row.setAction("USER_CREATE");
        row.setTargetType("USER");
        row.setTargetId("9");
        row.setHttpMethod("POST");
        row.setPath("/api/system-admin/users");
        row.setHttpStatus(200);
        row.setSuccess(true);
        row.setIp("10.0.0.7");
        row.setRequestId("req-1");
        row.setCreatedAt("2026-10-21T10:15:30");
        LogPageVO<AuditLogVO> page = new LogPageVO<>();
        page.setRecords(List.of(row));
        page.setTotal(41L);
        page.setPage(2);
        page.setSize(20);
        when(monitorService.listAuditLogs("ro", "USER_CREATE", 1,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), 2, 20)).thenReturn(page);

        mvc.perform(get(BASE + "/audit-logs").param("operator", "ro").param("action", "USER_CREATE")
                        .param("success", "1").param("from", "2026-10-01").param("to", "2026-10-07")
                        .param("page", "2").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(41))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.records[0].id").value(5))
                .andExpect(jsonPath("$.data.records[0].operatorId").value(1))
                .andExpect(jsonPath("$.data.records[0].operatorName").value("root"))
                .andExpect(jsonPath("$.data.records[0].operatorRole").value(3))
                .andExpect(jsonPath("$.data.records[0].action").value("USER_CREATE"))
                .andExpect(jsonPath("$.data.records[0].targetType").value("USER"))
                .andExpect(jsonPath("$.data.records[0].targetId").value("9"))
                .andExpect(jsonPath("$.data.records[0].httpMethod").value("POST"))
                .andExpect(jsonPath("$.data.records[0].path").value("/api/system-admin/users"))
                .andExpect(jsonPath("$.data.records[0].httpStatus").value(200))
                .andExpect(jsonPath("$.data.records[0].success").value(true))
                .andExpect(jsonPath("$.data.records[0].ip").value("10.0.0.7"))
                .andExpect(jsonPath("$.data.records[0].requestId").value("req-1"))
                .andExpect(jsonPath("$.data.records[0].createdAt").value("2026-10-21T10:15:30"));
    }

    @Test
    @DisplayName("GET /audit-logs：全部参数可省略（都以 null 透传，由 service 套默认值）")
    void auditLogs_allParamsOptional() throws Exception {
        LogPageVO<AuditLogVO> page = new LogPageVO<>();
        page.setRecords(List.of());
        page.setPage(1);
        page.setSize(20);
        when(monitorService.listAuditLogs(null, null, null, null, null, null, null)).thenReturn(page);

        mvc.perform(get(BASE + "/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("GET /audit-logs：日期格式不对 → 400，不进 service")
    void auditLogs_badDate_returns400() throws Exception {
        mvc.perform(get(BASE + "/audit-logs").param("from", "2026/10/01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(monitorService);
    }

    @Test
    @DisplayName("GET /audit-logs：service 抛出的 400（如起始日晚于结束日）原样返回")
    void auditLogs_serviceBadRequest_propagates() throws Exception {
        when(monitorService.listAuditLogs(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ResultCode.BAD_REQUEST, "起始日期不能晚于结束日期"));

        mvc.perform(get(BASE + "/audit-logs"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("起始日期不能晚于结束日期"));
    }

    // ───────────────────────── GET /audit-logs/actions ─────────────────────────

    @Test
    @DisplayName("GET /audit-logs/actions：返回 string[]")
    void auditActions_returnsStringArray() throws Exception {
        when(monitorService.listAuditActions()).thenReturn(List.of("COURSE_CREATE", "USER_CREATE"));

        mvc.perform(get(BASE + "/audit-logs/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("COURSE_CREATE"))
                .andExpect(jsonPath("$.data[1]").value("USER_CREATE"));
    }

    // ───────────────────────── GET /login-logs ─────────────────────────

    @Test
    @DisplayName("GET /login-logs：参数透传，返回 LoginLogVO 全部字段")
    void loginLogs_passesParamsAndReturnsContract() throws Exception {
        LoginLogVO row = new LoginLogVO();
        row.setId(11L);
        row.setUserId(null);
        row.setUsername("ghost");
        row.setSuccess(false);
        row.setFailReason("BAD_CREDENTIALS");
        row.setIp("10.0.0.9");
        row.setUserAgent("Mozilla/5.0");
        row.setCreatedAt("2026-10-21T09:00:01");
        LogPageVO<LoginLogVO> page = new LogPageVO<>();
        page.setRecords(List.of(row));
        page.setTotal(1L);
        page.setPage(1);
        page.setSize(50);
        when(monitorService.listLoginLogs("gho", 0, LocalDate.of(2026, 10, 20), null, 1, 50)).thenReturn(page);

        mvc.perform(get(BASE + "/login-logs").param("username", "gho").param("success", "0")
                        .param("from", "2026-10-20").param("page", "1").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.records[0].id").value(11))
                .andExpect(jsonPath("$.data.records[0].userId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].username").value("ghost"))
                .andExpect(jsonPath("$.data.records[0].success").value(false))
                .andExpect(jsonPath("$.data.records[0].failReason").value("BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.data.records[0].ip").value("10.0.0.9"))
                .andExpect(jsonPath("$.data.records[0].userAgent").value("Mozilla/5.0"))
                .andExpect(jsonPath("$.data.records[0].createdAt").value("2026-10-21T09:00:01"));
    }

    // ───────────────────────── GET /overview ─────────────────────────

    @Test
    @DisplayName("GET /overview：days 透传；返回结构与方案逐字段一致")
    void overview_contract() throws Exception {
        OverviewVO vo = new OverviewVO();
        OverviewVO.Users users = new OverviewVO.Users();
        users.setTotal(10);
        users.setTeachers(2);
        users.setStudents(7);
        users.setAdmins(1);
        users.setDisabled(3);
        vo.setUsers(users);
        OverviewVO.Courses courses = new OverviewVO.Courses();
        courses.setTotal(6);
        courses.setPublished(3);
        courses.setDraft(2);
        courses.setArchived(1);
        vo.setCourses(courses);
        OverviewVO.ActivityDay day = new OverviewVO.ActivityDay();
        day.setDate("2026-10-21");
        day.setLogins(5);
        day.setActiveUsers(3);
        day.setFailures(2);
        OverviewVO.Activity activity = new OverviewVO.Activity();
        activity.setTodayActiveUsers(3);
        activity.setTodayLoginFailures(2);
        activity.setDaily(List.of(day));
        vo.setActivity(activity);
        OverviewVO.AiDay aiDay = new OverviewVO.AiDay();
        aiDay.setDate("2026-10-21");
        aiDay.setRuns(4);
        aiDay.setTokens(900);
        OverviewVO.AiKindStat kind = new OverviewVO.AiKindStat();
        kind.setKind("COMPANION_CHAT");
        kind.setRuns(30);
        kind.setTokens(3000);
        OverviewVO.AiModelStat model = new OverviewVO.AiModelStat();
        model.setModel("deepseek-v4-pro");
        model.setRuns(38);
        model.setTokens(3400);
        OverviewVO.JobKindStat jobKind = new OverviewVO.JobKindStat();
        jobKind.setKind("GRAPH_EXTRACT");
        jobKind.setCount(5);
        OverviewVO.Jobs jobs = new OverviewVO.Jobs();
        jobs.setTotal(9);
        jobs.setSucceeded(6);
        jobs.setFailed(2);
        jobs.setByKind(List.of(jobKind));
        OverviewVO.Ai ai = new OverviewVO.Ai();
        ai.setRuns(40);
        ai.setFailedRuns(4);
        ai.setTokensIn(1000);
        ai.setTokensOut(2500);
        ai.setAvgDurationMs(820);
        ai.setDaily(List.of(aiDay));
        ai.setByKind(List.of(kind));
        ai.setByModel(List.of(model));
        ai.setJobs(jobs);
        vo.setAi(ai);
        when(monitorService.overview(14)).thenReturn(vo);

        mvc.perform(get(BASE + "/overview").param("days", "14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.users.total").value(10))
                .andExpect(jsonPath("$.data.users.teachers").value(2))
                .andExpect(jsonPath("$.data.users.students").value(7))
                .andExpect(jsonPath("$.data.users.admins").value(1))
                .andExpect(jsonPath("$.data.users.disabled").value(3))
                .andExpect(jsonPath("$.data.courses.total").value(6))
                .andExpect(jsonPath("$.data.courses.published").value(3))
                .andExpect(jsonPath("$.data.courses.draft").value(2))
                .andExpect(jsonPath("$.data.courses.archived").value(1))
                .andExpect(jsonPath("$.data.activity.todayActiveUsers").value(3))
                .andExpect(jsonPath("$.data.activity.todayLoginFailures").value(2))
                .andExpect(jsonPath("$.data.activity.daily[0].date").value("2026-10-21"))
                .andExpect(jsonPath("$.data.activity.daily[0].logins").value(5))
                .andExpect(jsonPath("$.data.activity.daily[0].activeUsers").value(3))
                .andExpect(jsonPath("$.data.activity.daily[0].failures").value(2))
                .andExpect(jsonPath("$.data.ai.runs").value(40))
                .andExpect(jsonPath("$.data.ai.failedRuns").value(4))
                .andExpect(jsonPath("$.data.ai.tokensIn").value(1000))
                .andExpect(jsonPath("$.data.ai.tokensOut").value(2500))
                .andExpect(jsonPath("$.data.ai.avgDurationMs").value(820))
                .andExpect(jsonPath("$.data.ai.daily[0].date").value("2026-10-21"))
                .andExpect(jsonPath("$.data.ai.daily[0].runs").value(4))
                .andExpect(jsonPath("$.data.ai.daily[0].tokens").value(900))
                .andExpect(jsonPath("$.data.ai.byKind[0].kind").value("COMPANION_CHAT"))
                .andExpect(jsonPath("$.data.ai.byKind[0].runs").value(30))
                .andExpect(jsonPath("$.data.ai.byKind[0].tokens").value(3000))
                .andExpect(jsonPath("$.data.ai.byModel[0].model").value("deepseek-v4-pro"))
                .andExpect(jsonPath("$.data.ai.byModel[0].runs").value(38))
                .andExpect(jsonPath("$.data.ai.byModel[0].tokens").value(3400))
                .andExpect(jsonPath("$.data.ai.jobs.total").value(9))
                .andExpect(jsonPath("$.data.ai.jobs.succeeded").value(6))
                .andExpect(jsonPath("$.data.ai.jobs.failed").value(2))
                .andExpect(jsonPath("$.data.ai.jobs.byKind[0].kind").value("GRAPH_EXTRACT"))
                .andExpect(jsonPath("$.data.ai.jobs.byKind[0].count").value(5));
    }

    @Test
    @DisplayName("GET /overview：days 可省略（null 透传）")
    void overview_daysOptional() throws Exception {
        when(monitorService.overview(isNull())).thenReturn(new OverviewVO());

        mvc.perform(get(BASE + "/overview")).andExpect(status().isOk());

        verify(monitorService).overview(null);
    }

    // ───────────────────────── 鉴权 ─────────────────────────

    @Test
    @DisplayName("非管理员（教师/学生）→ 403；匿名 → 401；四个接口都不进 service")
    void nonAdmin_isRejected() throws Exception {
        String[] paths = {"/audit-logs", "/audit-logs/actions", "/login-logs", "/overview"};

        CurrentUser.set(new CurrentPrincipal(2L, UserRole.TEACHER));
        for (String path : paths) {
            mvc.perform(get(BASE + path)).andExpect(status().isForbidden());
        }
        CurrentUser.set(new CurrentPrincipal(3L, UserRole.STUDENT));
        for (String path : paths) {
            mvc.perform(get(BASE + path)).andExpect(status().isForbidden());
        }
        CurrentUser.clear();
        for (String path : paths) {
            mvc.perform(get(BASE + path)).andExpect(status().isUnauthorized());
        }

        verifyNoInteractions(monitorService);
    }
}
