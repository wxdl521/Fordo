package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.FeatureFlagsProperties;
import com.wenjin.dto.TeacherCourseVO;
import com.wenjin.entity.Course;
import com.wenjin.entity.KgEdge;
import com.wenjin.entity.KgNode;
import com.wenjin.mapper.CourseAccessMapper;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.KgEdgeMapper;
import com.wenjin.mapper.KgNodeMapper;
import com.wenjin.security.CourseAccessService;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.service.TeacherCourseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class TeacherCourseServiceImpl implements TeacherCourseService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CourseMapper courseMapper;
    private final KgNodeMapper nodeMapper;
    private final KgEdgeMapper edgeMapper;
    private final CourseAccessService courseAccessService;
    private final CourseAccessMapper courseAccessMapper;
    private final FeatureFlagsProperties featureFlags;

    public TeacherCourseServiceImpl(CourseMapper courseMapper, KgNodeMapper nodeMapper,
                                    KgEdgeMapper edgeMapper,
                                    CourseAccessService courseAccessService,
                                    CourseAccessMapper courseAccessMapper,
                                    FeatureFlagsProperties featureFlags) {
        this.courseMapper = courseMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.courseAccessService = courseAccessService;
        this.courseAccessMapper = courseAccessMapper;
        this.featureFlags = featureFlags;
    }

    @Override
    public List<TeacherCourseVO> list() {
        CurrentPrincipal principal = CurrentUser.requireTeacher();
        LambdaQueryWrapper<Course> q = new LambdaQueryWrapper<Course>()
                .isNull(Course::getArchivedAt)
                .orderByAsc(Course::getId);
        // 教师仅见自己的课；ADMIN 可列全部
        if (!principal.isAdmin()) {
            q.eq(Course::getTeacherId, principal.userId());
        }
        return courseMapper.selectList(q)
                .stream()
                .map(c -> new TeacherCourseVO(c.getId(), c.getCode(), c.getName(),
                        c.getStatus() != null && c.getStatus() == 1))
                .collect(Collectors.toList());
    }

    @Override
    public TeacherCourseVO create(String name) {
        CurrentPrincipal principal = CurrentUser.requireTeacher();
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "课程名不能为空");
        }
        Course c = new Course();
        c.setCode(generateUniqueCode());
        c.setName(name.trim());
        // 始终以当前主体为 owner，禁止客户端指定 teacherId
        c.setTeacherId(principal.userId());
        c.setStatus(0);
        courseMapper.insert(c);
        return new TeacherCourseVO(c.getId(), c.getCode(), c.getName(), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long courseId) {
        ensureGraphReconcileEnabled();
        courseAccessService.assertManageCourse(courseId);
        if (courseAccessMapper.lockActiveCourse(courseId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
        courseAccessService.assertManageCourse(courseId);
        Course c = courseMapper.selectById(courseId);
        if (c == null || c.getArchivedAt() != null) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }

        LocalDateTime now = LocalDateTime.now();
        // 软归档课程；不硬删题目 / 掌握度 / 路径等下游数据
        c.setStatus(0);
        c.setArchivedAt(now);
        courseMapper.updateById(c);

        // 归档关联节点与边
        nodeMapper.update(null, new UpdateWrapper<KgNode>()
                .eq("course_id", courseId)
                .isNull("archived_at")
                .set("archived_at", now));
        edgeMapper.update(null, new UpdateWrapper<KgEdge>()
                .eq("course_id", courseId)
                .isNull("archived_at")
                .set("archived_at", now));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TeacherCourseVO restore(Long courseId) {
        ensureGraphReconcileEnabled();
        courseAccessService.assertManageCourseIncludingArchived(courseId);
        if (courseAccessMapper.lockCourseIncludingArchived(courseId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
        courseAccessService.assertManageCourseIncludingArchived(courseId);

        Course c = courseMapper.selectById(courseId);
        if (c == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }

        courseMapper.update(null, new UpdateWrapper<Course>()
                .eq("id", courseId)
                .set("status", 0)
                .set("archived_at", null));
        if (c.getArchivedAt() != null) {
            nodeMapper.update(null, new UpdateWrapper<KgNode>()
                    .eq("course_id", courseId)
                    .set("archived_at", null));
            edgeMapper.update(null, new UpdateWrapper<KgEdge>()
                    .eq("course_id", courseId)
                    .set("archived_at", null));
        }

        return new TeacherCourseVO(c.getId(), c.getCode(), c.getName(), false);
    }

    @Override
    public void setPublished(Long courseId, boolean published) {
        courseAccessService.assertManageCourse(courseId);
        Course c = courseMapper.selectById(courseId);
        if (c == null || c.getArchivedAt() != null) {
            throw new BusinessException(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.getMessage());
        }
        c.setStatus(published ? 1 : 0);
        courseMapper.updateById(c);
    }

    private void ensureGraphReconcileEnabled() {
        if (featureFlags == null || !featureFlags.isGraphReconcileEnabled()) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "图谱对账/导入功能暂未开放");
        }
    }

    private String generateUniqueCode() {
        for (int i = 0; i < 20; i++) {
            String code = randomCode();
            Long n = courseMapper.selectCount(
                    new LambdaQueryWrapper<Course>().eq(Course::getCode, code));
            if (n == null || n == 0) {
                return code;
            }
        }
        throw new BusinessException(ResultCode.INTERNAL_ERROR, "课程编码生成失败，请重试");
    }

    static String randomCode() {
        byte[] b = new byte[5];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(10);
        for (byte x : b) {
            sb.append(String.format("%02X", x));
        }
        return sb.toString();
    }
}
