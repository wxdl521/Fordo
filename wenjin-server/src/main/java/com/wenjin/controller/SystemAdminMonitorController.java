package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.AuditLogVO;
import com.wenjin.dto.LogPageVO;
import com.wenjin.dto.LoginLogVO;
import com.wenjin.dto.OverviewVO;
import com.wenjin.service.MonitorService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 管理后台审计与监控（受 {@code AdminAuthInterceptor} 保护，仅系统管理员）。
 * 全部只读；薄层：参数透传，筛选/分页/聚合规则在 {@link MonitorService}。
 */
@RestController
@RequestMapping("/api/system-admin")
public class SystemAdminMonitorController {

    private final MonitorService monitorService;

    public SystemAdminMonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    /**
     * GET /api/system-admin/audit-logs
     * <p>{@code from}/{@code to} 为 yyyy-MM-dd，均含当日；{@code size} 上限 100。
     */
    @GetMapping("/audit-logs")
    public Result<LogPageVO<AuditLogVO>> auditLogs(
            @RequestParam(value = "operator", required = false) String operator,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "success", required = false) Integer success,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return Result.ok(monitorService.listAuditLogs(operator, action, success, from, to, page, size));
    }

    /** GET /api/system-admin/audit-logs/actions：库里出现过的 action 去重，供筛选下拉。 */
    @GetMapping("/audit-logs/actions")
    public Result<List<String>> auditActions() {
        return Result.ok(monitorService.listAuditActions());
    }

    /** GET /api/system-admin/login-logs */
    @GetMapping("/login-logs")
    public Result<LogPageVO<LoginLogVO>> loginLogs(
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "success", required = false) Integer success,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return Result.ok(monitorService.listLoginLogs(username, success, from, to, page, size));
    }

    /** GET /api/system-admin/overview?days=7（1–30，缺省 7）。 */
    @GetMapping("/overview")
    public Result<OverviewVO> overview(@RequestParam(value = "days", required = false) Integer days) {
        return Result.ok(monitorService.overview(days));
    }
}
