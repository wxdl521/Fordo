package com.wenjin.service;

import com.wenjin.dto.AdminCourseVO;
import com.wenjin.dto.AdminPageVO;
import com.wenjin.dto.AdminTeacherVO;

import java.util.List;

/**
 * 管理后台课程管理（仅 ADMIN 可调用，由 {@code AdminAuthInterceptor} 在路由层保证）。
 *
 * <p>归档 / 恢复 / 发布下架<b>委托</b> {@link TeacherCourseService}（管理员已通过其 assertManageCourse），
 * 这里只新增：含归档的全量列表、为指定教师建课、转让 owner。</p>
 */
public interface AdminCourseService {

    /**
     * 分页列表，按 id 倒序。{@code archived}：true=仅归档, false=仅未归档, null=全部。
     * page/size 越界时被钳制（size 上限 100）。
     */
    AdminPageVO<AdminCourseVO> list(String keyword, Integer status, Boolean archived, Long teacherId,
                                    Integer page, Integer size);

    /** 为指定的启用教师新建课程（草稿状态，code 自动生成）。 */
    AdminCourseVO create(String name, Long teacherId);

    /** 转让课程 owner 给另一位启用中的教师（归档课程也可转让）。 */
    AdminCourseVO transferOwner(Long courseId, Long teacherId);

    /** 发布 / 下架（委托 TeacherCourseService.setPublished）。 */
    AdminCourseVO setPublished(Long courseId, boolean published);

    /** 软归档（委托 TeacherCourseService.delete）。 */
    AdminCourseVO archive(Long courseId);

    /** 恢复归档课程，恢复后为未发布（委托 TeacherCourseService.restore）。 */
    AdminCourseVO restore(Long courseId);

    /** 启用中的教师，供"任课教师"下拉。 */
    List<AdminTeacherVO> listTeachers();
}
