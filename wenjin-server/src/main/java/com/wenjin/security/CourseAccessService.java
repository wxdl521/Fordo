package com.wenjin.security;

import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.mapper.CourseAccessMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 教师端课程所有权隔离：统一把 course / node / edge / question 解析到课程并校验归属。
 *
 * <ul>
 *   <li>资源不存在，或存在但当前教师非 owner → 统一 404（防枚举）</li>
 *   <li>ADMIN 明确旁路归属检查（仍要求资源存在）</li>
 *   <li>遗留课程 teacher_id 为空 → 仅 ADMIN 可管理</li>
 * </ul>
 */
@Service
public class CourseAccessService {

    private final CourseAccessMapper accessMapper;

    public CourseAccessService(CourseAccessMapper accessMapper) {
        this.accessMapper = accessMapper;
    }

    /** 断言当前主体可管理该课程。 */
    public void assertManageCourse(Long courseId) {
        CurrentPrincipal principal = CurrentUser.requireTeacher();
        if (courseId == null || courseId <= 0) {
            notFound();
        }
        if (principal.isAdmin()) {
            if (!accessMapper.courseExists(courseId)) {
                notFound();
            }
            return;
        }
        if (!accessMapper.isOwnedCourse(courseId, principal.userId())) {
            notFound();
        }
    }

    /**
     * 按业务编码断言可管理，返回课程主键。
     *
     * @return courseId
     */
    public Long assertManageCourseCode(String courseCode) {
        CurrentPrincipal principal = CurrentUser.requireTeacher();
        if (!StringUtils.hasText(courseCode)) {
            notFound();
        }
        Long courseId = accessMapper.courseIdByCode(courseCode.trim());
        if (courseId == null) {
            notFound();
        }
        if (principal.isAdmin()) {
            return courseId;
        }
        if (!accessMapper.isOwnedCourse(courseId, principal.userId())) {
            notFound();
        }
        return courseId;
    }

    /**
     * 断言可管理该节点所属课程。
     *
     * @return courseId
     */
    public Long assertManageNode(Long nodeId) {
        CurrentPrincipal principal = CurrentUser.requireTeacher();
        if (nodeId == null || nodeId <= 0) {
            notFound();
        }
        Long courseId = accessMapper.courseIdForNode(nodeId);
        if (courseId == null) {
            notFound();
        }
        assertOwnedOrAdmin(principal, courseId);
        return courseId;
    }

    /**
     * 断言可管理该边所属课程。
     *
     * @return courseId
     */
    public Long assertManageEdge(Long edgeId) {
        CurrentPrincipal principal = CurrentUser.requireTeacher();
        if (edgeId == null || edgeId <= 0) {
            notFound();
        }
        Long courseId = accessMapper.courseIdForEdge(edgeId);
        if (courseId == null) {
            notFound();
        }
        assertOwnedOrAdmin(principal, courseId);
        return courseId;
    }

    /**
     * 断言可管理该题目所属课程。
     *
     * @return courseId
     */
    public Long assertManageQuestion(Long questionId) {
        CurrentPrincipal principal = CurrentUser.requireTeacher();
        if (questionId == null || questionId <= 0) {
            notFound();
        }
        Long courseId = accessMapper.courseIdForQuestion(questionId);
        if (courseId == null) {
            notFound();
        }
        assertOwnedOrAdmin(principal, courseId);
        return courseId;
    }

    /**
     * 批量审核：先断言可管理目标课程，再校验题目须全部存在且仅属于该课程。
     * 缺失 id、跨课程、或无课程管理权 → 404。
     */
    public void assertQuestionsBelongToCourse(Long courseId, Collection<Long> questionIds) {
        assertManageCourse(courseId);
        if (questionIds == null || questionIds.isEmpty()) {
            return;
        }
        // 去重后计数，避免重复 id 导致 count 偏差
        Set<Long> unique = new HashSet<>();
        for (Long id : questionIds) {
            if (id != null && id > 0) {
                unique.add(id);
            }
        }
        if (unique.isEmpty()) {
            return;
        }
        long inCourse = accessMapper.countQuestionsInCourse(courseId, unique);
        if (inCourse != unique.size()) {
            notFound();
        }
    }

    /**
     * 批量解析题目所属课程集合（用于校验是否单一目标课）。
     * 返回的集合大小若小于有效 id 数，说明有题目不存在。
     */
    public List<Long> resolveQuestionCourseIds(Collection<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            return List.of();
        }
        return accessMapper.courseIdsForQuestions(questionIds);
    }

    private void assertOwnedOrAdmin(CurrentPrincipal principal, Long courseId) {
        if (principal.isAdmin()) {
            return;
        }
        if (!accessMapper.isOwnedCourse(courseId, principal.userId())) {
            notFound();
        }
    }

    private static void notFound() {
        throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
    }
}
