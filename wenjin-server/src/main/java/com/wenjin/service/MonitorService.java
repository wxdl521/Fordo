package com.wenjin.service;

import com.wenjin.dto.AuditLogVO;
import com.wenjin.dto.AdminPageVO;
import com.wenjin.dto.LoginLogVO;
import com.wenjin.dto.OverviewVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 管理后台审计与监控（只读）：操作日志、登录日志、平台总览。
 */
public interface MonitorService {

    /**
     * 操作日志分页，按时间倒序。
     *
     * @param operator 操作人用户名（模糊）
     * @param action   动作编码（精确）
     * @param success  1=成功 / 0=失败，null 不过滤，其它值 400
     * @param from     起始日期（含当日）
     * @param to       结束日期（含当日）
     * @param page     页码，缺省/小于 1 取 1
     * @param size     每页条数，缺省 20，上限 100
     */
    AdminPageVO<AuditLogVO> listAuditLogs(String operator, String action, Integer success,
                                        LocalDate from, LocalDate to, Integer page, Integer size);

    /** 库里出现过的 action 去重（供筛选下拉）。 */
    List<String> listAuditActions();

    /** 登录日志分页，按时间倒序；参数语义同 {@link #listAuditLogs}，{@code username} 为模糊匹配。 */
    AdminPageVO<LoginLogVO> listLoginLogs(String username, Integer success,
                                        LocalDate from, LocalDate to, Integer page, Integer size);

    /**
     * 平台总览。
     *
     * @param days 统计窗口天数（含今天），缺省 7，范围 1–30（越界钳制）
     */
    OverviewVO overview(Integer days);
}
