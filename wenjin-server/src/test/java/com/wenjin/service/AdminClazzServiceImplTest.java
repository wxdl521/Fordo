package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.AdminPageVO;
import com.wenjin.dto.AdminRows;
import com.wenjin.dto.ClazzAddStudentsResultVO;
import com.wenjin.dto.ClazzCourseEnrollVO;
import com.wenjin.dto.ClazzCourseVO;
import com.wenjin.dto.ClazzDetailVO;
import com.wenjin.dto.ClazzStudentVO;
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
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.impl.AdminClazzServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AdminClazzServiceImpl：班级 CRUD、加学生（含转班 / 非学生拒绝）、班级关联课程补选课、
 * 以及"移除 / 解除 / 删除都不动 student_course"。
 */
@ExtendWith(MockitoExtension.class)
class AdminClazzServiceImplTest {

    private static final long CLAZZ_ID = 100L;

    @Mock
    private ClazzMapper clazzMapper;
    @Mock
    private AdminClazzMapper adminClazzMapper;
    @Mock
    private ClazzStudentMapper clazzStudentMapper;
    @Mock
    private ClazzCourseMapper clazzCourseMapper;
    @Mock
    private StudentCourseMapper studentCourseMapper;
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private CourseMapper courseMapper;

    private AdminClazzServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminClazzServiceImpl(clazzMapper, adminClazzMapper, clazzStudentMapper,
                clazzCourseMapper, studentCourseMapper, userMapper, courseMapper);
        CurrentUser.set(new CurrentPrincipal(1L, UserRole.ADMIN));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    // ───────────────────────── list / detail ─────────────────────────

    @Test
    @DisplayName("list：关键字 trim + 转义，size 钳到 100，第 2 页偏移 100；计数映射（null→0）")
    void list_clampsEscapesAndMapsCounts() {
        AdminRows.ClazzRow r = clazzRow(CLAZZ_ID, "软工2201");
        r.setStudentCount(30L);
        r.setCourseCount(null);
        when(adminClazzMapper.countClazzes("a\\_b\\%")).thenReturn(101L);
        when(adminClazzMapper.selectClazzPage("a\\_b\\%", 100L, 100)).thenReturn(List.of(r));

        AdminPageVO<ClazzVO> page = service.list("  a_b%  ", 2, 999);

        assertThat(page.getTotal()).isEqualTo(101L);
        assertThat(page.getPage()).isEqualTo(2);
        assertThat(page.getSize()).isEqualTo(100);
        ClazzVO vo = page.getRecords().get(0);
        assertThat(vo.getId()).isEqualTo(CLAZZ_ID);
        assertThat(vo.getName()).isEqualTo("软工2201");
        assertThat(vo.getStudentCount()).isEqualTo(30L);
        assertThat(vo.getCourseCount()).isZero();
        assertThat(vo.getCreatedAt()).isEqualTo("2026-09-01T08:30:00");
    }

    @Test
    @DisplayName("list：默认 page=1,size=20；空关键字不筛；total=0 不查分页")
    void list_defaults() {
        when(adminClazzMapper.countClazzes(null)).thenReturn(0L);

        AdminPageVO<ClazzVO> page = service.list("  ", null, null);

        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(20);
        assertThat(page.getRecords()).isEmpty();
        verify(adminClazzMapper, never()).selectClazzPage(any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("detail：基本信息 + 成员 + 关联课程；不存在 → 404")
    void detail_assemblesMembersAndCourses() {
        AdminRows.ClazzRow r = clazzRow(CLAZZ_ID, "软工2201");
        r.setStudentCount(1L);
        r.setCourseCount(1L);
        ClazzStudentVO s = new ClazzStudentVO();
        s.setId(3L);
        s.setUsername("s1");
        s.setStatus(1);
        ClazzCourseVO c = new ClazzCourseVO();
        c.setId(5L);
        c.setName("软件工程");
        c.setStatus(1);
        when(adminClazzMapper.selectClazzById(CLAZZ_ID)).thenReturn(r);
        when(adminClazzMapper.selectMembers(CLAZZ_ID)).thenReturn(List.of(s));
        when(adminClazzMapper.selectLinkedCourses(CLAZZ_ID)).thenReturn(List.of(c));

        ClazzDetailVO vo = service.detail(CLAZZ_ID);

        assertThat(vo.getName()).isEqualTo("软工2201");
        assertThat(vo.getStudentCount()).isEqualTo(1L);
        assertThat(vo.getCourseCount()).isEqualTo(1L);
        assertThat(vo.getStudents()).containsExactly(s);
        assertThat(vo.getCourses()).containsExactly(c);

        when(adminClazzMapper.selectClazzById(404L)).thenReturn(null);
        assertCode(() -> service.detail(404L), ResultCode.NOT_FOUND);
    }

    // ───────────────────────── create / update / delete ─────────────────────────

    @Test
    @DisplayName("create：名称 trim、空白说明存 NULL")
    void create_trimsAndNullsBlankDescription() {
        when(clazzMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(clazzMapper.insert(any(Clazz.class))).thenAnswer(inv -> {
            ((Clazz) inv.getArgument(0)).setId(CLAZZ_ID);
            return 1;
        });
        when(adminClazzMapper.selectClazzById(CLAZZ_ID)).thenReturn(clazzRow(CLAZZ_ID, "软工2201"));

        ClazzVO vo = service.create("  软工2201 ", "   ");

        ArgumentCaptor<Clazz> cap = ArgumentCaptor.forClass(Clazz.class);
        verify(clazzMapper).insert(cap.capture());
        assertThat(cap.getValue().getName()).isEqualTo("软工2201");
        assertThat(cap.getValue().getDescription()).isNull();
        assertThat(vo.getId()).isEqualTo(CLAZZ_ID);
    }

    @Test
    @DisplayName("create：班级名重复（预检命中或并发撞唯一键）→ 400「班级名已存在」")
    void create_duplicateName_400() {
        when(clazzMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L, 0L);
        when(clazzMapper.insert(any(Clazz.class))).thenThrow(new DuplicateKeyException("uk_clazz_name"));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.create("软工2201", null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("班级名已存在")
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ResultCode.BAD_REQUEST.getCode());
        }
        verify(clazzMapper).insert(any(Clazz.class));
    }

    @Test
    @DisplayName("update：锁班级行后按字符串列名写 name/description（description 可清空为 NULL），排除自身查重")
    void update_writesNameAndDescription() {
        when(clazzMapper.selectByIdForUpdate(CLAZZ_ID)).thenReturn(clazz(CLAZZ_ID, "旧名"));
        when(clazzMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(adminClazzMapper.selectClazzById(CLAZZ_ID)).thenReturn(clazzRow(CLAZZ_ID, "新名"));

        ClazzVO vo = service.update(CLAZZ_ID, " 新名 ", null);

        ArgumentCaptor<QueryWrapper<Clazz>> dup = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(clazzMapper).selectCount(dup.capture());
        assertThat(dup.getValue().getSqlSegment()).contains("name").contains("id <>");
        ArgumentCaptor<UpdateWrapper<Clazz>> cap = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(clazzMapper).update(eq(null), cap.capture());
        UpdateWrapper<Clazz> w = cap.getValue();
        assertThat(w.getSqlSegment()).contains("id =");
        assertThat(w.getSqlSet()).contains("name=").contains("description=");
        assertThat(w.getParamNameValuePairs().values()).contains("新名", CLAZZ_ID);
        assertThat(w.getParamNameValuePairs().values()).containsNull();
        assertThat(vo.getName()).isEqualTo("新名");
    }

    @Test
    @DisplayName("update：改成别的班已用的名字 → 400；班级不存在 → 404")
    void update_duplicateOrMissing() {
        when(clazzMapper.selectByIdForUpdate(CLAZZ_ID)).thenReturn(clazz(CLAZZ_ID, "旧名"));
        when(clazzMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(clazzMapper.selectByIdForUpdate(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(CLAZZ_ID, "软工2202", "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("班级名已存在");
        assertCode(() -> service.update(404L, "x", null), ResultCode.NOT_FOUND);
        verify(clazzMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("delete：锁班级行，删课程关联 / 成员 / 班级本身；不碰 student_course")
    void delete_hardDeletesClazzOnly() {
        when(clazzMapper.selectByIdForUpdate(CLAZZ_ID)).thenReturn(clazz(CLAZZ_ID, "软工2201"));

        service.delete(CLAZZ_ID);

        InOrder order = inOrder(clazzMapper, clazzCourseMapper, clazzStudentMapper);
        order.verify(clazzMapper).selectByIdForUpdate(CLAZZ_ID);
        order.verify(clazzCourseMapper).delete(any(QueryWrapper.class));
        order.verify(clazzStudentMapper).delete(any(QueryWrapper.class));
        order.verify(clazzMapper).deleteById(CLAZZ_ID);
        verifyNoInteractions(studentCourseMapper);
    }

    @Test
    @DisplayName("delete：班级不存在 → 404，不删任何东西")
    void delete_missing_404() {
        when(clazzMapper.selectByIdForUpdate(404L)).thenReturn(null);

        assertCode(() -> service.delete(404L), ResultCode.NOT_FOUND);
        verifyNoInteractions(clazzCourseMapper, clazzStudentMapper, studentCourseMapper);
        verify(clazzMapper, never()).deleteById(anyLong());
    }

    // ───────────────────────── addStudents ─────────────────────────

    @Test
    @DisplayName("addStudents(usernames)：新学生插入、别班学生转班并报告 movedFrom、本班学生跳过、"
            + "非学生/不存在进 notFound；新进入者一条 SQL 补选班级课程")
    void addStudents_byUsernames_insertMoveSkipAndEnroll() {
        when(clazzMapper.selectByIdForUpdate(CLAZZ_ID)).thenReturn(clazz(CLAZZ_ID, "软工2201"));
        SysUser fresh = user(3L, "s1", UserRole.STUDENT);
        SysUser mover = user(4L, "S2", UserRole.STUDENT);
        SysUser stayer = user(5L, "s3", UserRole.STUDENT);
        SysUser teacher = user(9L, "t1", UserRole.TEACHER);
        when(userMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(fresh, mover, stayer, teacher));
        when(clazzStudentMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(member(101L, 4L), member(CLAZZ_ID, 5L)));
        when(clazzMapper.selectBatchIds(anyCollection())).thenReturn(List.of(clazz(101L, "软工2202")));
        when(studentCourseMapper.enrollStudentsInClazzCourses(eq(CLAZZ_ID), anyCollection())).thenReturn(4);

        ClazzAddStudentsResultVO result = service.addStudents(CLAZZ_ID, null,
                List.of(" s1 ", "s2", "S1", "", "s3", "t1", "ghost"));

        assertThat(result.getAdded()).isEqualTo(2);
        assertThat(result.getMovedFrom()).singleElement().satisfies(m -> {
            assertThat(m.getStudentId()).isEqualTo(4L);
            assertThat(m.getFromClazzName()).isEqualTo("软工2202");
        });
        assertThat(result.getNotFound()).containsExactly("t1", "ghost");
        assertThat(result.getEnrolled()).isEqualTo(4);

        verify(clazzStudentMapper).insertBatch(CLAZZ_ID, List.of(3L));
        verify(clazzStudentMapper).moveToClazz(CLAZZ_ID, List.of(4L));
        ArgumentCaptor<Collection<Long>> enrolled = ArgumentCaptor.forClass(Collection.class);
        verify(studentCourseMapper).enrollStudentsInClazzCourses(eq(CLAZZ_ID), enrolled.capture());
        assertThat(enrolled.getValue()).containsExactlyInAnyOrder(3L, 4L);

        // 用户名查询：trim、去空行、大小写不敏感去重后的 5 个用户名
        ArgumentCaptor<QueryWrapper<SysUser>> uq = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(userMapper).selectList(uq.capture());
        assertThat(uq.getValue().getSqlSegment()).contains("username IN");
        assertThat(uq.getValue().getParamNameValuePairs().values())
                .containsExactlyInAnyOrder("s1", "s2", "s3", "t1", "ghost");
        // 成员行加锁读
        ArgumentCaptor<QueryWrapper<ClazzStudent>> mq = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(clazzStudentMapper).selectList(mq.capture());
        assertThat(mq.getValue().getCustomSqlSegment()).contains("FOR UPDATE");
    }

    @Test
    @DisplayName("addStudents(studentIds)：不存在 / 非学生的 id 以字符串进 notFound；全新学生只插入不转班")
    void addStudents_byIds() {
        when(clazzMapper.selectByIdForUpdate(CLAZZ_ID)).thenReturn(clazz(CLAZZ_ID, "软工2201"));
        when(userMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(user(3L, "s1", UserRole.STUDENT), user(6L, "adm", UserRole.ADMIN)));
        when(clazzStudentMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(studentCourseMapper.enrollStudentsInClazzCourses(eq(CLAZZ_ID), anyCollection())).thenReturn(0);

        ClazzAddStudentsResultVO result = service.addStudents(CLAZZ_ID, List.of(3L, 6L, 77L, 3L), null);

        assertThat(result.getAdded()).isEqualTo(1);
        assertThat(result.getMovedFrom()).isEmpty();
        assertThat(result.getNotFound()).containsExactly("6", "77");
        assertThat(result.getEnrolled()).isZero();
        verify(clazzStudentMapper).insertBatch(CLAZZ_ID, List.of(3L));
        verify(clazzStudentMapper, never()).moveToClazz(anyLong(), anyCollection());
        verify(clazzMapper, never()).selectBatchIds(anyCollection());
    }

    @Test
    @DisplayName("addStudents：全是非学生 / 不存在 → 不写成员、不补选课")
    void addStudents_nobodyValid_writesNothing() {
        when(clazzMapper.selectByIdForUpdate(CLAZZ_ID)).thenReturn(clazz(CLAZZ_ID, "软工2201"));
        when(userMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(user(9L, "t1", UserRole.TEACHER)));

        ClazzAddStudentsResultVO result = service.addStudents(CLAZZ_ID, null, List.of("t1", "ghost"));

        assertThat(result.getAdded()).isZero();
        assertThat(result.getNotFound()).containsExactly("t1", "ghost");
        verifyNoInteractions(clazzStudentMapper, studentCourseMapper);
    }

    @Test
    @DisplayName("addStudents：全员已在本班 → added=0，不写库")
    void addStudents_allAlreadyMembers() {
        when(clazzMapper.selectByIdForUpdate(CLAZZ_ID)).thenReturn(clazz(CLAZZ_ID, "软工2201"));
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user(3L, "s1", UserRole.STUDENT)));
        when(clazzStudentMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(member(CLAZZ_ID, 3L)));

        ClazzAddStudentsResultVO result = service.addStudents(CLAZZ_ID, List.of(3L), null);

        assertThat(result.getAdded()).isZero();
        assertThat(result.getEnrolled()).isZero();
        verify(clazzStudentMapper, never()).insertBatch(anyLong(), anyCollection());
        verify(clazzStudentMapper, never()).moveToClazz(anyLong(), anyCollection());
        verifyNoInteractions(studentCourseMapper);
    }

    @Test
    @DisplayName("addStudents：studentIds 与 usernames 同时给 / 都不给 / 只有空白 → 400；不锁班级")
    void addStudents_badInput_400() {
        assertCode(() -> service.addStudents(CLAZZ_ID, List.of(3L), List.of("s1")), ResultCode.BAD_REQUEST);
        assertCode(() -> service.addStudents(CLAZZ_ID, null, null), ResultCode.BAD_REQUEST);
        assertCode(() -> service.addStudents(CLAZZ_ID, List.of(), List.of()), ResultCode.BAD_REQUEST);
        verifyNoInteractions(clazzMapper);

        when(clazzMapper.selectByIdForUpdate(CLAZZ_ID)).thenReturn(clazz(CLAZZ_ID, "软工2201"));
        assertCode(() -> service.addStudents(CLAZZ_ID, null, List.of("  ", "")), ResultCode.BAD_REQUEST);
        List<Long> nullIds = new ArrayList<>();
        nullIds.add(null);
        assertCode(() -> service.addStudents(CLAZZ_ID, nullIds, null), ResultCode.BAD_REQUEST);
        verifyNoInteractions(userMapper, clazzStudentMapper, studentCourseMapper);
    }

    @Test
    @DisplayName("addStudents：去重后超过 500 人 → 400")
    void addStudents_overLimit_400() {
        when(clazzMapper.selectByIdForUpdate(CLAZZ_ID)).thenReturn(clazz(CLAZZ_ID, "软工2201"));
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            names.add("u" + i);
        }

        assertThatThrownBy(() -> service.addStudents(CLAZZ_ID, null, names))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("500");
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("addStudents：并发把同一学生放进别班撞唯一键 → 409（整体事务回滚）")
    void addStudents_concurrentConflict_409() {
        when(clazzMapper.selectByIdForUpdate(CLAZZ_ID)).thenReturn(clazz(CLAZZ_ID, "软工2201"));
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user(3L, "s1", UserRole.STUDENT)));
        when(clazzStudentMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(clazzStudentMapper.insertBatch(eq(CLAZZ_ID), anyCollection()))
                .thenThrow(new DuplicateKeyException("uk_clazz_student_student"));

        assertCode(() -> service.addStudents(CLAZZ_ID, List.of(3L), null), ResultCode.CONFLICT);
        verifyNoInteractions(studentCourseMapper);
    }

    @Test
    @DisplayName("addStudents：班级不存在 → 404")
    void addStudents_clazzMissing_404() {
        when(clazzMapper.selectByIdForUpdate(404L)).thenReturn(null);

        assertCode(() -> service.addStudents(404L, List.of(3L), null), ResultCode.NOT_FOUND);
        verifyNoInteractions(userMapper, clazzStudentMapper, studentCourseMapper);
    }

    // ───────────────────────── removeStudent ─────────────────────────

    @Test
    @DisplayName("removeStudent：只删成员行，保留 student_course；不在本班 → 404")
    void removeStudent_keepsEnrollments() {
        when(clazzMapper.selectByIdForUpdate(CLAZZ_ID)).thenReturn(clazz(CLAZZ_ID, "软工2201"));
        when(clazzStudentMapper.delete(any(QueryWrapper.class))).thenReturn(1, 0);

        service.removeStudent(CLAZZ_ID, 3L);
        assertCode(() -> service.removeStudent(CLAZZ_ID, 4L), ResultCode.NOT_FOUND);

        ArgumentCaptor<QueryWrapper<ClazzStudent>> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(clazzStudentMapper, times(2)).delete(cap.capture());
        assertThat(cap.getAllValues().get(0).getSqlSegment()).contains("clazz_id").contains("student_id");
        assertThat(cap.getAllValues().get(0).getParamNameValuePairs().values()).contains(CLAZZ_ID, 3L);
        verifyNoInteractions(studentCourseMapper);
    }

    // ───────────────────────── addCourse / removeCourse ─────────────────────────

    @Test
    @DisplayName("addCourse：建关联后一条 INSERT IGNORE…SELECT 为全班补选，返回新增行数；未发布课程同样允许")
    void addCourse_linksAndEnrollsMembers() {
        when(clazzMapper.selectByIdForUpdate(CLAZZ_ID)).thenReturn(clazz(CLAZZ_ID, "软工2201"));
        Course draft = new Course();
        draft.setId(5L);
        draft.setStatus(0);
        when(courseMapper.selectById(5L)).thenReturn(draft);
        when(clazzCourseMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(clazzCourseMapper.insert(any(ClazzCourse.class))).thenReturn(1);
        when(studentCourseMapper.enrollClazzMembersInCourse(CLAZZ_ID, 5L)).thenReturn(28);

        ClazzCourseEnrollVO vo = service.addCourse(CLAZZ_ID, 5L);

        assertThat(vo.getEnrolled()).isEqualTo(28);
        ArgumentCaptor<ClazzCourse> cap = ArgumentCaptor.forClass(ClazzCourse.class);
        InOrder order = inOrder(clazzCourseMapper, studentCourseMapper);
        order.verify(clazzCourseMapper).insert(cap.capture());
        order.verify(studentCourseMapper).enrollClazzMembersInCourse(CLAZZ_ID, 5L);
        assertThat(cap.getValue().getClazzId()).isEqualTo(CLAZZ_ID);
        assertThat(cap.getValue().getCourseId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("addCourse：课程不存在 → 404；已归档 → 400；已关联（预检或撞唯一键）→ 400；均不补选课")
    void addCourse_rejections() {
        when(clazzMapper.selectByIdForUpdate(CLAZZ_ID)).thenReturn(clazz(CLAZZ_ID, "软工2201"));
        Course archived = new Course();
        archived.setId(6L);
        archived.setArchivedAt(LocalDateTime.now());
        Course live = new Course();
        live.setId(5L);
        live.setStatus(1);
        when(courseMapper.selectById(404L)).thenReturn(null);
        when(courseMapper.selectById(6L)).thenReturn(archived);
        when(courseMapper.selectById(5L)).thenReturn(live);
        when(clazzCourseMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L, 0L);
        when(clazzCourseMapper.insert(any(ClazzCourse.class))).thenThrow(new DuplicateKeyException("uk_clazz_course"));

        assertCode(() -> service.addCourse(CLAZZ_ID, 404L), ResultCode.NOT_FOUND);
        assertThatThrownBy(() -> service.addCourse(CLAZZ_ID, 6L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已归档");
        assertThatThrownBy(() -> service.addCourse(CLAZZ_ID, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已关联");
        assertThatThrownBy(() -> service.addCourse(CLAZZ_ID, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已关联");
        verifyNoInteractions(studentCourseMapper);
    }

    @Test
    @DisplayName("removeCourse：只删关联行，保留 student_course；未关联 → 404")
    void removeCourse_keepsEnrollments() {
        when(clazzMapper.selectByIdForUpdate(CLAZZ_ID)).thenReturn(clazz(CLAZZ_ID, "软工2201"));
        when(clazzCourseMapper.delete(any(QueryWrapper.class))).thenReturn(1, 0);

        service.removeCourse(CLAZZ_ID, 5L);
        assertCode(() -> service.removeCourse(CLAZZ_ID, 6L), ResultCode.NOT_FOUND);

        verifyNoInteractions(studentCourseMapper);
    }

    // ───────────────────────── helpers ─────────────────────────

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ResultCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(code.getCode());
    }

    private static AdminRows.ClazzRow clazzRow(Long id, String name) {
        AdminRows.ClazzRow r = new AdminRows.ClazzRow();
        r.setId(id);
        r.setName(name);
        r.setCreatedAt(LocalDateTime.of(2026, 9, 1, 8, 30, 0));
        r.setStudentCount(0L);
        r.setCourseCount(0L);
        return r;
    }

    private static Clazz clazz(Long id, String name) {
        Clazz z = new Clazz();
        z.setId(id);
        z.setName(name);
        return z;
    }

    private static ClazzStudent member(Long clazzId, Long studentId) {
        ClazzStudent cs = new ClazzStudent();
        cs.setClazzId(clazzId);
        cs.setStudentId(studentId);
        return cs;
    }

    private static SysUser user(Long id, String username, UserRole role) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername(username);
        u.setRole(role.code());
        u.setStatus(1);
        return u;
    }
}
