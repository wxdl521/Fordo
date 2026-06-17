package com.wenjin.service;

import com.wenjin.dto.DashboardVO;

/** 学情看板服务【阶段六】。 */
public interface TeacherDashboardService {
    DashboardVO dashboard(Long courseId);
}
