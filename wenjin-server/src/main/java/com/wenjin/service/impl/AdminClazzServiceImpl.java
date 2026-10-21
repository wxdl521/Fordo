package com.wenjin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.AdminPageVO;
import com.wenjin.dto.AdminRows;
import com.wenjin.dto.ClazzAddStudentsResultVO;
import com.wenjin.dto.ClazzCourseEnrollVO;
import com.wenjin.dto.ClazzDetailVO;
import com.wenjin.dto.ClazzVO;
import com.wenjin.entity.Clazz;
import com.wenjin.entity.ClazzCourse;
import com.wenjin.entity.ClazzStudent;
import com.wenjin.entity.Course;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.AdminClazzMapper;
import com.wenjin.mapper.ClazzCourseMapper;
import com.wenjin.mapper.ClazzMapper;
import com.wenjin.mapper.ClazzStudentMapper;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.StudentCourseMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.UserRole;
import com.wenjin.service.AdminClazzService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 管理后台班级管理实现。
 *
 * <p>班级是"批量选课"的手段，不替代自选：补选课一律是一条
 * {@code INSERT IGNORE ... SELECT}（见 {@link StudentCourseMapper}），已选的由 uk_student_course 忽略；
 * 移除学生 / 解除课程 / 删除班级都不删除 student_course。</p>
 *
 * <p>所有改动成员 / 课程 / 班级本身的写操作先 {@code SELECT ... FOR UPDATE} 锁班级行，
 * 与"删除班级"串行化；每个写操作是单个事务，要么全成要么全回滚。</p>
 */
@Service
public class AdminClazzServiceImpl implements AdminClazzService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int NAME_MAX = 64;
    private static final int DESCRIPTION_MAX = 255;
    private static final int MAX_BATCH = 500;

    private final ClazzMapper clazzMapper;
    private final AdminClazzMapper adminClazzMapper;
    private final ClazzStudentMapper clazzStudentMapper;
    private final ClazzCourseMapper clazzCourseMapper;
    private final StudentCourseMapper studentCourseMapper;
    private final SysUserMapper userMapper;
    private final CourseMapper courseMapper;

    public AdminClazzServiceImpl(ClazzMapper clazzMapper, AdminClazzMapper adminClazzMapper,
                                 ClazzStudentMapper clazzStudentMapper, ClazzCourseMapper clazzCourseMapper,
                                 StudentCourseMapper studentCourseMapper, SysUserMapper userMapper,
                                 CourseMapper courseMapper) {
        this.clazzMapper = clazzMapper;
        this.adminClazzMapper = adminClazzMapper;
        this.clazzStudentMapper = clazzStudentMapper;
        this.clazzCourseMapper = clazzCourseMapper;
        this.studentCourseMapper = studentCourseMapper;
        this.userMapper = userMapper;
        this.courseMapper = courseMapper;
    }

    // ───────────────────────── 班级本身 ─────────────────────────

    @Override
    public AdminPageVO<ClazzVO> list(String keyword, Integer page, Integer size) {
        int effectivePage = (page == null || page < 1) ? 1 : page;
        int effectiveSize = (size == null || size < 1) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        String k = keyword == null ? "" : keyword.trim();
        String like = k.isEmpty() ? null : escapeLike(k);

        long total = adminClazzMapper.countClazzes(like);
        List<ClazzVO> records = Collections.emptyList();
        if (total > 0) {
            long offset = (long) (effectivePage - 1) * effectiveSize;
            List<AdminRows.ClazzRow> rows = adminClazzMapper.selectClazzPage(like, offset, effectiveSize);
            records = new ArrayList<>(rows.size());
            for (AdminRows.ClazzRow row : rows) {
                records.add(toVO(row));
            }
        }

        AdminPageVO<ClazzVO> vo = new AdminPageVO<>();
        vo.setRecords(records);
        vo.setTotal(total);
        vo.setPage(effectivePage);
        vo.setSize(effectiveSize);
        return vo;
    }

    @Override
    public ClazzVO create(String name, String description) {
        String n = normalizeName(name);
        String d = normalizeDescription(description);
        if (nameTaken(n, null)) {
            throw nameExists();
        }
        Clazz z = new Clazz();
        z.setName(n);
        z.setDescription(d);
        try {
            clazzMapper.insert(z);
        } catch (DuplicateKeyException e) {
            throw nameExists();
        }
        return requireVO(z.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClazzVO update(Long id, String name, String description) {
        requireClazzLocked(id);
        String n = normalizeName(name);
        String d = normalizeDescription(description);
        if (nameTaken(n, id)) {
            throw nameExists();
        }
        try {
            // 字符串列名 + set：description 为 null 时也要真的写成 NULL（补丁实体 updateById 会忽略 null）
            clazzMapper.update(null, new UpdateWrapper<Clazz>()
                    .eq("id", id)
                    .set("name", n)
                    .set("description", d));
        } catch (DuplicateKeyException e) {
            throw nameExists();
        }
        return requireVO(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireClazzLocked(id);
        // 外键本身是 ON DELETE CASCADE；显式删子行让"不动 student_course"的语义一目了然
        clazzCourseMapper.delete(new QueryWrapper<ClazzCourse>().eq("clazz_id", id));
        clazzStudentMapper.delete(new QueryWrapper<ClazzStudent>().eq("clazz_id", id));
        clazzMapper.deleteById(id);
    }

    @Override
    public ClazzDetailVO detail(Long id) {
        AdminRows.ClazzRow row = adminClazzMapper.selectClazzById(id);
        if (row == null) {
            throw clazzNotFound();
        }
        ClazzDetailVO vo = new ClazzDetailVO();
        vo.setId(row.getId());
        vo.setName(row.getName());
        vo.setDescription(row.getDescription());
        vo.setStudentCount(n(row.getStudentCount()));
        vo.setCourseCount(n(row.getCourseCount()));
        vo.setCreatedAt(iso(row.getCreatedAt()));
        vo.setStudents(adminClazzMapper.selectMembers(id));
        vo.setCourses(adminClazzMapper.selectLinkedCourses(id));
        return vo;
    }

    // ───────────────────────── 成员 ─────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClazzAddStudentsResultVO addStudents(Long id, List<Long> studentIds, List<String> usernames) {
        boolean hasIds = studentIds != null && !studentIds.isEmpty();
        boolean hasNames = usernames != null && !usernames.isEmpty();
        if (hasIds && hasNames) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "studentIds 与 usernames 只能二选一");
        }
        if (!hasIds && !hasNames) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请提供 studentIds 或 usernames");
        }
        requireClazzLocked(id);

        ClazzAddStudentsResultVO result = new ClazzAddStudentsResultVO();
        // 通过校验的学生，按输入顺序、去重
        Map<Long, SysUser> students = hasIds
                ? resolveByIds(studentIds, result.getNotFound())
                : resolveByUsernames(usernames, result.getNotFound());
        if (students.isEmpty()) {
            return result;
        }

        // 这些学生当前所在的班级（锁住这些成员行，转班期间不被并发改动）
        List<Long> candidateIds = new ArrayList<>(students.keySet());
        Map<Long, Long> currentClazz = new HashMap<>();
        for (ClazzStudent cs : clazzStudentMapper.selectList(new QueryWrapper<ClazzStudent>()
                .in("student_id", candidateIds)
                .last("FOR UPDATE"))) {
            currentClazz.put(cs.getStudentId(), cs.getClazzId());
        }

        List<Long> toInsert = new ArrayList<>();
        List<Long> toMove = new ArrayList<>();
        for (Long sid : candidateIds) {
            Long cur = currentClazz.get(sid);
            if (cur == null) {
                toInsert.add(sid);
            } else if (!cur.equals(id)) {
                toMove.add(sid);
            }
            // cur == id：本来就在该班，跳过
        }

        Map<Long, String> fromNames = toMove.isEmpty()
                ? Collections.emptyMap()
                : clazzNames(currentClazz, toMove);
        for (Long sid : toMove) {
            result.getMovedFrom().add(new ClazzAddStudentsResultVO.MovedFrom(
                    sid, fromNames.getOrDefault(currentClazz.get(sid), "")));
        }

        try {
            if (!toInsert.isEmpty()) {
                clazzStudentMapper.insertBatch(id, toInsert);
            }
            if (!toMove.isEmpty()) {
                clazzStudentMapper.moveToClazz(id, toMove);
            }
        } catch (DuplicateKeyException e) {
            // uk_clazz_student_student：并发请求刚把同一个学生放进了别的班级
            throw new BusinessException(ResultCode.CONFLICT, "部分学生刚被加入其他班级，请重试");
        }

        List<Long> joined = new ArrayList<>(toInsert);
        joined.addAll(toMove);
        result.setAdded(joined.size());
        if (!joined.isEmpty()) {
            result.setEnrolled(studentCourseMapper.enrollStudentsInClazzCourses(id, joined));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeStudent(Long id, Long studentId) {
        requireClazzLocked(id);
        int deleted = clazzStudentMapper.delete(new QueryWrapper<ClazzStudent>()
                .eq("clazz_id", id)
                .eq("student_id", studentId));
        if (deleted == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "该学生不在此班级");
        }
        // 不删 student_course：已选课程与学习数据保留
    }

    // ───────────────────────── 课程 ─────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClazzCourseEnrollVO addCourse(Long id, Long courseId) {
        requireClazzLocked(id);
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");
        }
        if (course.getArchivedAt() != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "课程已归档，无法关联");
        }
        Long linked = clazzCourseMapper.selectCount(new QueryWrapper<ClazzCourse>()
                .eq("clazz_id", id)
                .eq("course_id", courseId));
        if (linked != null && linked > 0) {
            throw courseAlreadyLinked();
        }

        ClazzCourse link = new ClazzCourse();
        link.setClazzId(id);
        link.setCourseId(courseId);
        try {
            clazzCourseMapper.insert(link);
        } catch (DuplicateKeyException e) {
            throw courseAlreadyLinked();
        }
        // 未发布的课程也先选上：学生端能不能进仍由 course.status 决定
        return new ClazzCourseEnrollVO(studentCourseMapper.enrollClazzMembersInCourse(id, courseId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeCourse(Long id, Long courseId) {
        requireClazzLocked(id);
        int deleted = clazzCourseMapper.delete(new QueryWrapper<ClazzCourse>()
                .eq("clazz_id", id)
                .eq("course_id", courseId));
        if (deleted == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "该课程未关联到此班级");
        }
        // 不删 student_course：学生仍可继续学
    }

    // ───────────────────────── helpers ─────────────────────────

    /** 按 id 解析：存在且是学生才算数；其余按 id 字符串进 notFound。 */
    private Map<Long, SysUser> resolveByIds(List<Long> studentIds, List<String> notFound) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Long sid : studentIds) {
            if (sid != null) {
                ids.add(sid);
            }
        }
        if (ids.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请提供 studentIds 或 usernames");
        }
        requireBatchSize(ids.size());
        Map<Long, SysUser> found = new HashMap<>();
        for (SysUser u : userMapper.selectBatchIds(ids)) {
            found.put(u.getId(), u);
        }
        Map<Long, SysUser> students = new LinkedHashMap<>();
        for (Long sid : ids) {
            SysUser u = found.get(sid);
            if (isStudent(u)) {
                students.put(sid, u);
            } else {
                notFound.add(String.valueOf(sid));
            }
        }
        return students;
    }

    /** 按用户名解析：trim、忽略空行、大小写不敏感去重（数据库唯一键也是大小写不敏感）。 */
    private Map<Long, SysUser> resolveByUsernames(List<String> usernames, List<String> notFound) {
        Map<String, String> tokens = new LinkedHashMap<>();
        for (String raw : usernames) {
            String t = raw == null ? "" : raw.trim();
            if (!t.isEmpty()) {
                tokens.putIfAbsent(t.toLowerCase(Locale.ROOT), t);
            }
        }
        if (tokens.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请提供 studentIds 或 usernames");
        }
        requireBatchSize(tokens.size());

        Map<String, SysUser> found = new HashMap<>();
        for (SysUser u : userMapper.selectList(new QueryWrapper<SysUser>().in("username", tokens.values()))) {
            found.put(u.getUsername().toLowerCase(Locale.ROOT), u);
        }
        Map<Long, SysUser> students = new LinkedHashMap<>();
        for (Map.Entry<String, String> token : tokens.entrySet()) {
            SysUser u = found.get(token.getKey());
            if (isStudent(u)) {
                students.putIfAbsent(u.getId(), u);
            } else {
                notFound.add(token.getValue());
            }
        }
        return students;
    }

    private static void requireBatchSize(int n) {
        if (n > MAX_BATCH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "一次最多添加 " + MAX_BATCH + " 人");
        }
    }

    private static boolean isStudent(SysUser u) {
        return u != null && u.getRole() != null && u.getRole() == UserRole.STUDENT.code();
    }

    /** 转班来源班级 id → 班级名（一次批量查询）。 */
    private Map<Long, String> clazzNames(Map<Long, Long> currentClazz, List<Long> movedStudentIds) {
        Set<Long> fromIds = new LinkedHashSet<>();
        for (Long sid : movedStudentIds) {
            fromIds.add(currentClazz.get(sid));
        }
        Map<Long, String> names = new HashMap<>();
        for (Clazz z : clazzMapper.selectBatchIds(fromIds)) {
            names.put(z.getId(), z.getName());
        }
        return names;
    }

    private Clazz requireClazzLocked(Long id) {
        Clazz z = clazzMapper.selectByIdForUpdate(id);
        if (z == null) {
            throw clazzNotFound();
        }
        return z;
    }

    private ClazzVO requireVO(Long id) {
        AdminRows.ClazzRow row = adminClazzMapper.selectClazzById(id);
        if (row == null) {
            throw clazzNotFound();
        }
        return toVO(row);
    }

    /** 同名班级是否已存在（excludeId 非空时排除自身）。 */
    private boolean nameTaken(String name, Long excludeId) {
        QueryWrapper<Clazz> w = new QueryWrapper<Clazz>().eq("name", name);
        if (excludeId != null) {
            w.ne("id", excludeId);
        }
        Long n = clazzMapper.selectCount(w);
        return n != null && n > 0;
    }

    private static String normalizeName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "班级名不能为空");
        }
        if (n.length() > NAME_MAX) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "班级名长度不能超过 " + NAME_MAX);
        }
        return n;
    }

    private static String normalizeDescription(String description) {
        String d = description == null ? "" : description.trim();
        if (d.isEmpty()) {
            return null;
        }
        if (d.length() > DESCRIPTION_MAX) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "班级说明长度不能超过 " + DESCRIPTION_MAX);
        }
        return d;
    }

    private static BusinessException clazzNotFound() {
        return new BusinessException(ResultCode.NOT_FOUND, "班级不存在");
    }

    private static BusinessException nameExists() {
        return new BusinessException(ResultCode.BAD_REQUEST, "班级名已存在");
    }

    private static BusinessException courseAlreadyLinked() {
        return new BusinessException(ResultCode.BAD_REQUEST, "该课程已关联到此班级");
    }

    private static ClazzVO toVO(AdminRows.ClazzRow row) {
        ClazzVO vo = new ClazzVO();
        vo.setId(row.getId());
        vo.setName(row.getName());
        vo.setDescription(row.getDescription());
        vo.setStudentCount(n(row.getStudentCount()));
        vo.setCourseCount(n(row.getCourseCount()));
        vo.setCreatedAt(iso(row.getCreatedAt()));
        return vo;
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
