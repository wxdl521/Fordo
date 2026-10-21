package com.wenjin.service;

import com.wenjin.dto.AdminPageVO;
import com.wenjin.dto.ClazzAddStudentsResultVO;
import com.wenjin.dto.ClazzCourseEnrollVO;
import com.wenjin.dto.ClazzDetailVO;
import com.wenjin.dto.ClazzVO;

import java.util.List;

/**
 * 管理后台班级管理（仅 ADMIN 可调用，由 {@code AdminAuthInterceptor} 在路由层保证）。
 *
 * <p>班级是"批量选课"的手段，不替代学生自选：
 * 课程关联到班级、学生加入班级时补 {@code student_course}（已存在跳过）；
 * 移除学生 / 解除班级课程 / 删除班级都<b>不</b>删除 {@code student_course}。</p>
 */
public interface AdminClazzService {

    /** 分页列表，按 id 倒序；keyword 对班级名模糊匹配。page/size 越界时被钳制（size 上限 100）。 */
    AdminPageVO<ClazzVO> list(String keyword, Integer page, Integer size);

    /** 新建班级；班级名重复 → 400「班级名已存在」。 */
    ClazzVO create(String name, String description);

    /** 修改班级名 / 说明（description 为空即清空）。 */
    ClazzVO update(Long id, String name, String description);

    /** 硬删班级及其成员、课程关联行；不动 student_course。 */
    void delete(Long id);

    /** 班级详情：基本信息 + 成员 + 关联课程。 */
    ClazzDetailVO detail(Long id);

    /**
     * 批量加学生，{@code studentIds} 与 {@code usernames} 二选一（各至多 500）。
     * 只接受学生角色；已在别的班级的转班；本来就在该班的跳过；整体单事务。
     * 加入 / 转入的学生会补选该班级已关联的全部课程。
     */
    ClazzAddStudentsResultVO addStudents(Long id, List<Long> studentIds, List<String> usernames);

    /** 把学生移出班级（保留其 student_course）。 */
    void removeStudent(Long id, Long studentId);

    /** 关联课程并为班级内全部学生补选该课程。 */
    ClazzCourseEnrollVO addCourse(Long id, Long courseId);

    /** 解除班级与课程的关联（保留已有 student_course）。 */
    void removeCourse(Long id, Long courseId);
}
