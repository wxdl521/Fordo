package com.wenjin.service.impl;

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
import com.wenjin.service.MonitorService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 管理后台审计与监控实现。
 *
 * <p>分页：项目未注册 MyBatis-Plus 分页插件，沿用 {@link AdminUserServiceImpl} 的手写 count +
 * {@code LIMIT offset,size}（两个数值都是服务端钳制后的整数，无注入面）。用字符串列名的 QueryWrapper，
 * 纯单测里也能直接断言 SQL。总览的聚合全部在 {@link MonitorMapper} 里 GROUP BY，这里只做补齐与映射。</p>
 */
@Service
public class MonitorServiceImpl implements MonitorService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private static final int DEFAULT_DAYS = 7;
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 30;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final AuditLogMapper auditLogMapper;
    private final LoginLogMapper loginLogMapper;
    private final MonitorMapper monitorMapper;
    private final Clock clock;

    public MonitorServiceImpl(AuditLogMapper auditLogMapper, LoginLogMapper loginLogMapper,
                              MonitorMapper monitorMapper, Clock clock) {
        this.auditLogMapper = auditLogMapper;
        this.loginLogMapper = loginLogMapper;
        this.monitorMapper = monitorMapper;
        this.clock = clock;
    }

    // ───────────────────────── 操作日志 ─────────────────────────

    @Override
    public AdminPageVO<AuditLogVO> listAuditLogs(String operator, String action, Integer success,
                                               LocalDate from, LocalDate to, Integer page, Integer size) {
        validateSuccess(success);
        validateRange(from, to);
        int effectivePage = effectivePage(page);
        int effectiveSize = effectiveSize(size);

        long total = auditLogMapper.selectCount(auditFilter(operator, action, success, from, to));
        List<AuditLogVO> records = Collections.emptyList();
        if (total > 0) {
            List<AuditLog> rows = auditLogMapper.selectList(
                    auditFilter(operator, action, success, from, to)
                            .orderByDesc("created_at", "id")
                            .last(limitClause(effectivePage, effectiveSize)));
            records = new ArrayList<>(rows.size());
            for (AuditLog row : rows) {
                records.add(toVO(row));
            }
        }
        return page(records, total, effectivePage, effectiveSize);
    }

    @Override
    public List<String> listAuditActions() {
        return auditLogMapper.selectDistinctActions();
    }

    private static QueryWrapper<AuditLog> auditFilter(String operator, String action, Integer success,
                                                      LocalDate from, LocalDate to) {
        QueryWrapper<AuditLog> w = new QueryWrapper<>();
        String op = trimToNull(operator);
        if (op != null) {
            w.like("operator_name", escapeLike(op));
        }
        String act = trimToNull(action);
        if (act != null) {
            w.eq("action", act);
        }
        applyCommon(w, success, from, to);
        return w;
    }

    // ───────────────────────── 登录日志 ─────────────────────────

    @Override
    public AdminPageVO<LoginLogVO> listLoginLogs(String username, Integer success,
                                               LocalDate from, LocalDate to, Integer page, Integer size) {
        validateSuccess(success);
        validateRange(from, to);
        int effectivePage = effectivePage(page);
        int effectiveSize = effectiveSize(size);

        long total = loginLogMapper.selectCount(loginFilter(username, success, from, to));
        List<LoginLogVO> records = Collections.emptyList();
        if (total > 0) {
            List<LoginLog> rows = loginLogMapper.selectList(
                    loginFilter(username, success, from, to)
                            .orderByDesc("created_at", "id")
                            .last(limitClause(effectivePage, effectiveSize)));
            records = new ArrayList<>(rows.size());
            for (LoginLog row : rows) {
                records.add(toVO(row));
            }
        }
        return page(records, total, effectivePage, effectiveSize);
    }

    private static QueryWrapper<LoginLog> loginFilter(String username, Integer success, LocalDate from, LocalDate to) {
        QueryWrapper<LoginLog> w = new QueryWrapper<>();
        String name = trimToNull(username);
        if (name != null) {
            w.like("username", escapeLike(name));
        }
        applyCommon(w, success, from, to);
        return w;
    }

    // ───────────────────────── 平台总览 ─────────────────────────

    @Override
    public OverviewVO overview(Integer days) {
        int effectiveDays = days == null ? DEFAULT_DAYS : Math.max(MIN_DAYS, Math.min(days, MAX_DAYS));
        LocalDate today = LocalDate.now(clock);
        LocalDate firstDay = today.minusDays(effectiveDays - 1L);
        LocalDateTime start = firstDay.atStartOfDay();

        List<String> dates = new ArrayList<>(effectiveDays);
        for (int i = 0; i < effectiveDays; i++) {
            dates.add(firstDay.plusDays(i).format(DAY));
        }

        OverviewVO vo = new OverviewVO();
        vo.setUsers(users());
        vo.setCourses(courses());
        vo.setActivity(activity(start, dates));
        vo.setAi(ai(start, dates));
        return vo;
    }

    private OverviewVO.Users users() {
        MonitorRows.UserCounts row = monitorMapper.countUsers();
        OverviewVO.Users out = new OverviewVO.Users();
        if (row != null) {
            out.setTotal(n(row.getTotal()));
            out.setTeachers(n(row.getTeachers()));
            out.setStudents(n(row.getStudents()));
            out.setAdmins(n(row.getAdmins()));
            out.setDisabled(n(row.getDisabled()));
        }
        return out;
    }

    private OverviewVO.Courses courses() {
        MonitorRows.CourseCounts row = monitorMapper.countCourses();
        OverviewVO.Courses out = new OverviewVO.Courses();
        if (row != null) {
            out.setTotal(n(row.getTotal()));
            out.setPublished(n(row.getPublished()));
            out.setDraft(n(row.getDraft()));
            out.setArchived(n(row.getArchived()));
        }
        return out;
    }

    private OverviewVO.Activity activity(LocalDateTime start, List<String> dates) {
        Map<String, MonitorRows.LoginDay> byDay = indexBy(monitorMapper.loginDaily(start), MonitorRows.LoginDay::getDay);
        List<OverviewVO.ActivityDay> daily = new ArrayList<>(dates.size());
        for (String date : dates) {
            MonitorRows.LoginDay row = byDay.get(date);
            OverviewVO.ActivityDay d = new OverviewVO.ActivityDay();
            d.setDate(date);
            if (row != null) {
                d.setLogins(n(row.getLogins()));
                d.setActiveUsers(n(row.getActiveUsers()));
                d.setFailures(n(row.getFailures()));
            }
            daily.add(d);
        }
        OverviewVO.ActivityDay today = daily.get(daily.size() - 1);
        OverviewVO.Activity out = new OverviewVO.Activity();
        out.setTodayActiveUsers(today.getActiveUsers());
        out.setTodayLoginFailures(today.getFailures());
        out.setDaily(daily);
        return out;
    }

    private OverviewVO.Ai ai(LocalDateTime start, List<String> dates) {
        OverviewVO.Ai out = new OverviewVO.Ai();
        MonitorRows.AiTotals totals = monitorMapper.aiTotals(start);
        if (totals != null) {
            out.setRuns(n(totals.getRuns()));
            out.setFailedRuns(n(totals.getFailedRuns()));
            out.setTokensIn(n(totals.getTokensIn()));
            out.setTokensOut(n(totals.getTokensOut()));
            out.setAvgDurationMs(n(totals.getAvgDurationMs()));
        }

        Map<String, MonitorRows.AiDay> byDay = indexBy(monitorMapper.aiDaily(start), MonitorRows.AiDay::getDay);
        List<OverviewVO.AiDay> daily = new ArrayList<>(dates.size());
        for (String date : dates) {
            MonitorRows.AiDay row = byDay.get(date);
            OverviewVO.AiDay d = new OverviewVO.AiDay();
            d.setDate(date);
            if (row != null) {
                d.setRuns(n(row.getRuns()));
                d.setTokens(n(row.getTokens()));
            }
            daily.add(d);
        }
        out.setDaily(daily);

        List<OverviewVO.AiKindStat> byKind = new ArrayList<>();
        for (MonitorRows.NameStat row : orEmpty(monitorMapper.aiByKind(start))) {
            OverviewVO.AiKindStat s = new OverviewVO.AiKindStat();
            s.setKind(row.getName());
            s.setRuns(n(row.getRuns()));
            s.setTokens(n(row.getTokens()));
            byKind.add(s);
        }
        out.setByKind(byKind);

        List<OverviewVO.AiModelStat> byModel = new ArrayList<>();
        for (MonitorRows.NameStat row : orEmpty(monitorMapper.aiByModel(start))) {
            OverviewVO.AiModelStat s = new OverviewVO.AiModelStat();
            s.setModel(row.getName());
            s.setRuns(n(row.getRuns()));
            s.setTokens(n(row.getTokens()));
            byModel.add(s);
        }
        out.setByModel(byModel);

        OverviewVO.Jobs jobs = new OverviewVO.Jobs();
        MonitorRows.JobTotals jobTotals = monitorMapper.jobTotals(start);
        if (jobTotals != null) {
            jobs.setTotal(n(jobTotals.getTotal()));
            jobs.setSucceeded(n(jobTotals.getSucceeded()));
            jobs.setFailed(n(jobTotals.getFailed()));
        }
        List<OverviewVO.JobKindStat> jobKinds = new ArrayList<>();
        for (MonitorRows.NameCount row : orEmpty(monitorMapper.jobsByKind(start))) {
            OverviewVO.JobKindStat s = new OverviewVO.JobKindStat();
            s.setKind(row.getName());
            s.setCount(n(row.getCnt()));
            jobKinds.add(s);
        }
        jobs.setByKind(jobKinds);
        out.setJobs(jobs);
        return out;
    }

    // ───────────────────────── helpers ─────────────────────────

    private static void validateSuccess(Integer success) {
        if (success != null && success != 0 && success != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "结果筛选值只能是 0 或 1");
        }
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "起始日期不能晚于结束日期");
        }
    }

    /** success / 日期范围：from 含当日 00:00，to 含当日整天（< 次日 00:00）。 */
    private static void applyCommon(QueryWrapper<?> w, Integer success, LocalDate from, LocalDate to) {
        if (success != null) {
            w.eq("success", success);
        }
        if (from != null) {
            w.ge("created_at", from.atStartOfDay());
        }
        if (to != null) {
            w.lt("created_at", to.plusDays(1).atStartOfDay());
        }
    }

    private static int effectivePage(Integer page) {
        return (page == null || page < 1) ? 1 : page;
    }

    private static int effectiveSize(Integer size) {
        return (size == null || size < 1) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    }

    private static String limitClause(int page, int size) {
        long offset = (long) (page - 1) * size;
        return "LIMIT " + offset + "," + size;
    }

    private static <T> AdminPageVO<T> page(List<T> records, long total, int page, int size) {
        AdminPageVO<T> vo = new AdminPageVO<>();
        vo.setRecords(records);
        vo.setTotal(total);
        vo.setPage(page);
        vo.setSize(size);
        return vo;
    }

    /** 转义 LIKE 通配符（MySQL 默认转义符为反斜杠），让用户输入的 % _ 按字面匹配。 */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static long n(Long v) {
        return v == null ? 0L : v;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private static <T> Map<String, T> indexBy(List<T> rows, Function<T, String> key) {
        Map<String, T> map = new HashMap<>();
        for (T row : orEmpty(rows)) {
            map.put(key.apply(row), row);
        }
        return map;
    }

    private static AuditLogVO toVO(AuditLog row) {
        AuditLogVO vo = new AuditLogVO();
        vo.setId(row.getId());
        vo.setOperatorId(row.getOperatorId());
        vo.setOperatorName(row.getOperatorName());
        vo.setOperatorRole(row.getOperatorRole());
        vo.setAction(row.getAction());
        vo.setTargetType(row.getTargetType());
        vo.setTargetId(row.getTargetId());
        vo.setHttpMethod(row.getHttpMethod());
        vo.setPath(row.getPath());
        vo.setHttpStatus(row.getHttpStatus());
        vo.setSuccess(row.getSuccess() != null && row.getSuccess() == 1);
        vo.setIp(row.getIp());
        vo.setRequestId(row.getRequestId());
        vo.setCreatedAt(iso(row.getCreatedAt()));
        return vo;
    }

    private static LoginLogVO toVO(LoginLog row) {
        LoginLogVO vo = new LoginLogVO();
        vo.setId(row.getId());
        vo.setUserId(row.getUserId());
        vo.setUsername(row.getUsername());
        vo.setSuccess(row.getSuccess() != null && row.getSuccess() == 1);
        vo.setFailReason(row.getFailReason());
        vo.setIp(row.getIp());
        vo.setUserAgent(row.getUserAgent());
        vo.setCreatedAt(iso(row.getCreatedAt()));
        return vo;
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(t);
    }
}
