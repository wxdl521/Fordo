package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.AdminCourseQuery;
import com.wenjin.dto.AdminCourseVO;
import com.wenjin.dto.AdminPageVO;
import com.wenjin.dto.AdminRows;
import com.wenjin.dto.AdminTeacherVO;
import com.wenjin.entity.Course;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.AdminCourseMapper;
import com.wenjin.mapper.CourseAccessMapper;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.UserRole;
import com.wenjin.service.AdminCourseService;
import com.wenjin.service.TeacherCourseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 管理后台课程管理实现。
 *
 * <p>分页：项目未注册 MyBatis-Plus 分页插件，沿用手写 count + {@code LIMIT offset,size}
 * （offset/size 是服务端钳制后的整数，经 MyBatis 参数绑定）。列表与单条查询共用同一段 SQL，
 * 计数全部是 GROUP BY 子查询，不 N+1。</p>
 *
 * <p>归档 / 恢复 / 发布下架一律委托 {@link TeacherCourseService}：加锁、级联归档节点和边、
 * 功能开关都只有那一份实现。这里只在委托前做一次状态预检，给管理员更明确的提示
 * （例如"课程已归档"，而不是教师端语义下的 404）；真正的校验仍在委托方的行锁内重做。</p>
 */
@Service
public class AdminCourseServiceImpl implements AdminCourseService {

    private static final int STATUS_DRAFT = 0;
    private static final int STATUS_PUBLISHED = 1;
    private static final int STATUS_ACTIVE_USER = 1;

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int NAME_MAX = 128;

    private final AdminCourseMapper adminCourseMapper;
    private final CourseMapper courseMapper;
    private final CourseAccessMapper courseAccessMapper;
    private final SysUserMapper userMapper;
    private final TeacherCourseService teacherCourseService;

    public AdminCourseServiceImpl(AdminCourseMapper adminCourseMapper, CourseMapper courseMapper,
                                  CourseAccessMapper courseAccessMapper, SysUserMapper userMapper,
                                  TeacherCourseService teacherCourseService) {
        this.adminCourseMapper = adminCourseMapper;
        this.courseMapper = courseMapper;
        this.courseAccessMapper = courseAccessMapper;
        this.userMapper = userMapper;
        this.teacherCourseService = teacherCourseService;
    }

    // ───────────────────────── 查询 ─────────────────────────

    @Override
    public AdminPageVO<AdminCourseVO> list(String keyword, Integer status, Boolean archived, Long teacherId,
                                           Integer page, Integer size) {
        if (status != null && status != STATUS_DRAFT && status != STATUS_PUBLISHED) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "状态筛选值无效");
        }
        int effectivePage = (page == null || page < 1) ? 1 : page;
        int effectiveSize = (size == null || size < 1) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        AdminCourseQuery q = new AdminCourseQuery();
        String k = keyword == null ? "" : keyword.trim();
        q.setKeyword(k.isEmpty() ? null : escapeLike(k));
        q.setStatus(status);
        q.setArchived(archived);
        q.setTeacherId(teacherId);
        q.setOffset((long) (effectivePage - 1) * effectiveSize);
        q.setSize(effectiveSize);

        long total = adminCourseMapper.countCourses(q);
        List<AdminCourseVO> records = Collections.emptyList();
        if (total > 0) {
            List<AdminRows.CourseRow> rows = adminCourseMapper.selectCoursePage(q);
            records = new ArrayList<>(rows.size());
            for (AdminRows.CourseRow row : rows) {
                records.add(toVO(row));
            }
        }

        AdminPageVO<AdminCourseVO> vo = new AdminPageVO<>();
        vo.setRecords(records);
        vo.setTotal(total);
        vo.setPage(effectivePage);
        vo.setSize(effectiveSize);
        return vo;
    }

    @Override
    public List<AdminTeacherVO> listTeachers() {
        List<SysUser> rows = userMapper.selectList(new QueryWrapper<SysUser>()
                .select("id", "username", "real_name")
                .eq("role", UserRole.TEACHER.code())
                .eq("status", STATUS_ACTIVE_USER)
                .orderByAsc("id"));
        List<AdminTeacherVO> out = new ArrayList<>(rows.size());
        for (SysUser u : rows) {
            out.add(new AdminTeacherVO(u.getId(), u.getUsername(), u.getRealName()));
        }
        return out;
    }

    // ───────────────────────── 写 ─────────────────────────

    @Override
    public AdminCourseVO create(String name, Long teacherId) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "课程名称不能为空");
        }
        if (trimmed.length() > NAME_MAX) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "课程名称长度不能超过 " + NAME_MAX);
        }
        requireActiveTeacher(teacherId);

        Course c = new Course();
        c.setCode(CourseCodes.generateUnique(courseMapper));
        c.setName(trimmed);
        c.setTeacherId(teacherId);
        c.setStatus(STATUS_DRAFT);
        courseMapper.insert(c);
        return requireVO(c.getId());
    }

    /**
     * 转让 owner。先 {@code SELECT ... FOR UPDATE} 锁课程行（含归档）：归档 / 恢复 / 发布委托方
     * 是"读整行再 updateById"，不与本操作串行化的话，会把刚转让的 teacher_id 写回旧值。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminCourseVO transferOwner(Long courseId, Long teacherId) {
        if (courseAccessMapper.lockCourseIncludingArchived(courseId) == null) {
            throw courseNotFound();
        }
        requireActiveTeacher(teacherId);

        Course patch = new Course();
        patch.setId(courseId);
        patch.setTeacherId(teacherId);
        courseMapper.updateById(patch);
        return requireVO(courseId);
    }

    @Override
    public AdminCourseVO setPublished(Long courseId, boolean published) {
        Course c = requireCourse(courseId);
        if (c.getArchivedAt() != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "课程已归档，请先恢复");
        }
        teacherCourseService.setPublished(courseId, published);
        return requireVO(courseId);
    }

    @Override
    public AdminCourseVO archive(Long courseId) {
        Course c = requireCourse(courseId);
        if (c.getArchivedAt() != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "课程已归档");
        }
        teacherCourseService.delete(courseId);
        return requireVO(courseId);
    }

    @Override
    public AdminCourseVO restore(Long courseId) {
        Course c = requireCourse(courseId);
        if (c.getArchivedAt() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "课程未归档，无需恢复");
        }
        teacherCourseService.restore(courseId);
        return requireVO(courseId);
    }

    // ───────────────────────── helpers ─────────────────────────

    /** 任课教师必须存在、是教师角色且处于启用状态。 */
    private void requireActiveTeacher(Long teacherId) {
        SysUser teacher = teacherId == null ? null : userMapper.selectById(teacherId);
        if (teacher == null
                || teacher.getRole() == null || teacher.getRole() != UserRole.TEACHER.code()
                || teacher.getStatus() == null || teacher.getStatus() != STATUS_ACTIVE_USER) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "任课教师无效：须是启用中的教师");
        }
    }

    private Course requireCourse(Long courseId) {
        Course c = courseMapper.selectById(courseId);
        if (c == null) {
            throw courseNotFound();
        }
        return c;
    }

    private AdminCourseVO requireVO(Long courseId) {
        AdminRows.CourseRow row = adminCourseMapper.selectCourseById(courseId);
        if (row == null) {
            throw courseNotFound();
        }
        return toVO(row);
    }

    private static BusinessException courseNotFound() {
        return new BusinessException(ResultCode.NOT_FOUND, "课程不存在");
    }

    private static AdminCourseVO toVO(AdminRows.CourseRow row) {
        AdminCourseVO vo = new AdminCourseVO();
        vo.setId(row.getId());
        vo.setCode(row.getCode());
        vo.setName(row.getName());
        vo.setTeacherId(row.getTeacherId());
        vo.setTeacherName(displayName(row.getTeacherRealName(), row.getTeacherUsername()));
        vo.setStatus(row.getStatus());
        vo.setArchived(row.getArchivedAt() != null);
        vo.setArchivedAt(iso(row.getArchivedAt()));
        vo.setNodeCount(n(row.getNodeCount()));
        vo.setStudentCount(n(row.getStudentCount()));
        vo.setClazzCount(n(row.getClazzCount()));
        vo.setCreatedAt(iso(row.getCreatedAt()));
        return vo;
    }

    private static String displayName(String realName, String username) {
        return realName != null && !realName.isBlank() ? realName : username;
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(t);
    }

    private static long n(Long v) {
        return v == null ? 0L : v;
    }

    /** 转义 LIKE 通配符（MySQL 默认转义符为反斜杠），让用户输入的 % _ 按字面匹配。 */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
