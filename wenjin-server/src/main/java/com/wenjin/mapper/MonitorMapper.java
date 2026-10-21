package com.wenjin.mapper;

import com.wenjin.dto.MonitorRows;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理后台「平台总览」的只读聚合查询。全部在库里 GROUP BY，绝不把全表拉进 Java。
 *
 * <p>时间窗口统一为 {@code created_at >= #{start}}（start = 今天-(days-1) 的 00:00，由调用方用注入的 Clock 算）。
 * 列别名用下划线，由 MyBatis 的驼峰映射落到 {@link MonitorRows} 的属性上。</p>
 */
@Mapper
public interface MonitorMapper {

    @Select("""
            SELECT COUNT(*)                          AS total,
                   COALESCE(SUM(role = 1), 0)        AS teachers,
                   COALESCE(SUM(role = 2), 0)        AS students,
                   COALESCE(SUM(role = 3), 0)        AS admins,
                   COALESCE(SUM(status = 0), 0)      AS disabled
            FROM sys_user
            """)
    MonitorRows.UserCounts countUsers();

    @Select("""
            SELECT COUNT(*)                                                AS total,
                   COALESCE(SUM(archived_at IS NULL AND status = 1), 0)    AS published,
                   COALESCE(SUM(archived_at IS NULL AND status = 0), 0)    AS draft,
                   COALESCE(SUM(archived_at IS NOT NULL), 0)               AS archived
            FROM course
            """)
    MonitorRows.CourseCounts countCourses();

    /** 登录日志按天：成功次数、当日成功登录的去重用户数、失败次数。 */
    @Select("""
            SELECT DATE_FORMAT(created_at, '%Y-%m-%d')                         AS day,
                   COALESCE(SUM(success = 1), 0)                               AS logins,
                   COUNT(DISTINCT CASE WHEN success = 1 THEN user_id END)      AS active_users,
                   COALESCE(SUM(success = 0), 0)                               AS failures
            FROM login_log
            WHERE created_at >= #{start}
            GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d')
            """)
    List<MonitorRows.LoginDay> loginDaily(@Param("start") LocalDateTime start);

    @Select("""
            SELECT COUNT(*)                                  AS runs,
                   COALESCE(SUM(status = 2), 0)              AS failed_runs,
                   COALESCE(SUM(tokens_in), 0)               AS tokens_in,
                   COALESCE(SUM(tokens_out), 0)              AS tokens_out,
                   COALESCE(ROUND(AVG(duration_ms)), 0)      AS avg_duration_ms
            FROM agent_run
            WHERE created_at >= #{start}
            """)
    MonitorRows.AiTotals aiTotals(@Param("start") LocalDateTime start);

    @Select("""
            SELECT DATE_FORMAT(created_at, '%Y-%m-%d')            AS day,
                   COUNT(*)                                       AS runs,
                   COALESCE(SUM(tokens_in + tokens_out), 0)       AS tokens
            FROM agent_run
            WHERE created_at >= #{start}
            GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d')
            """)
    List<MonitorRows.AiDay> aiDaily(@Param("start") LocalDateTime start);

    @Select("""
            SELECT kind                                           AS name,
                   COUNT(*)                                       AS runs,
                   COALESCE(SUM(tokens_in + tokens_out), 0)       AS tokens
            FROM agent_run
            WHERE created_at >= #{start}
            GROUP BY kind
            ORDER BY runs DESC, kind ASC
            """)
    List<MonitorRows.NameStat> aiByKind(@Param("start") LocalDateTime start);

    /** 没记录模型的 run（失败得早、仍在运行等）归入 "unknown"。 */
    @Select("""
            SELECT COALESCE(NULLIF(model, ''), 'unknown')         AS name,
                   COUNT(*)                                       AS runs,
                   COALESCE(SUM(tokens_in + tokens_out), 0)       AS tokens
            FROM agent_run
            WHERE created_at >= #{start}
            GROUP BY COALESCE(NULLIF(model, ''), 'unknown')
            ORDER BY runs DESC, name ASC
            """)
    List<MonitorRows.NameStat> aiByModel(@Param("start") LocalDateTime start);

    @Select("""
            SELECT COUNT(*)                          AS total,
                   COALESCE(SUM(status = 2), 0)      AS succeeded,
                   COALESCE(SUM(status = 3), 0)      AS failed
            FROM ai_job
            WHERE created_at >= #{start}
            """)
    MonitorRows.JobTotals jobTotals(@Param("start") LocalDateTime start);

    @Select("""
            SELECT kind AS name, COUNT(*) AS cnt
            FROM ai_job
            WHERE created_at >= #{start}
            GROUP BY kind
            ORDER BY cnt DESC, kind ASC
            """)
    List<MonitorRows.NameCount> jobsByKind(@Param("start") LocalDateTime start);
}
