package com.wenjin.integration;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 教师课程所有权隔离 — 真实 MySQL / Testcontainers 集成测试占位。
 *
 * <p>Task 3 阶段尚未引入 Testcontainers；归属逻辑由
 * {@link com.wenjin.security.CourseAccessServiceTest} 与各 service 单元测试覆盖
 * （跨教师 404、admin 旁路、无 owner 遗留课仅 admin）。
 *
 * <p>Task 5 引入 Testcontainers 后应在此验证：
 * <ul>
 *   <li>JOIN 将 edge/node/question 解析到 course.teacher_id</li>
 *   <li>teacher A 对 B 的资源统一 404</li>
 *   <li>admin 可管理任意课；teacher_id IS NULL 仅 admin</li>
 * </ul>
 */
@Disabled("Deferred until Testcontainers MySQL (Task 5); unit coverage in CourseAccessServiceTest")
class TeacherOwnershipIT {

    @Test
    @DisplayName("placeholder: cross-tenant edge accept returns 404 against real schema")
    void teacherCannotAcceptEdgeFromAnotherTeachersCourse_realDb() {
        // Task 5: seed teacher A/B courses + edge, call TeacherGraphService.acceptEdge
    }
}
