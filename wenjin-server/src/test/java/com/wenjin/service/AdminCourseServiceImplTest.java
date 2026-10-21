package com.wenjin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.AdminCourseQuery;
import com.wenjin.dto.AdminCourseVO;
import com.wenjin.dto.AdminPageVO;
import com.wenjin.dto.AdminRows;
import com.wenjin.dto.AdminTeacherVO;
import com.wenjin.dto.TeacherCourseVO;
import com.wenjin.entity.Course;
import com.wenjin.entity.SysUser;
import com.wenjin.mapper.AdminCourseMapper;
import com.wenjin.mapper.CourseAccessMapper;
import com.wenjin.mapper.CourseMapper;
import com.wenjin.mapper.SysUserMapper;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.impl.AdminCourseServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AdminCourseServiceImpl：含归档的全量列表 / 为指定教师建课 / 转让 owner / 发布·归档·恢复委托。
 */
@ExtendWith(MockitoExtension.class)
class AdminCourseServiceImplTest {

    @Mock
    private AdminCourseMapper adminCourseMapper;
    @Mock
    private CourseMapper courseMapper;
    @Mock
    private CourseAccessMapper courseAccessMapper;
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private TeacherCourseService teacherCourseService;

    private AdminCourseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminCourseServiceImpl(adminCourseMapper, courseMapper, courseAccessMapper,
                userMapper, teacherCourseService);
        CurrentUser.set(new CurrentPrincipal(1L, UserRole.ADMIN));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    // ───────────────────────── list ─────────────────────────

    @Test
    @DisplayName("list：筛选参数（转义关键字 / status / archived / teacherId）与钳制后的 offset/size 进入同一个查询对象")
    void list_buildsQueryAndClamps() {
        when(adminCourseMapper.countCourses(any(AdminCourseQuery.class))).thenReturn(250L);
        when(adminCourseMapper.selectCoursePage(any(AdminCourseQuery.class))).thenReturn(List.of());

        AdminPageVO<AdminCourseVO> page = service.list("  a_b%c  ", 1, true, 7L, 3, 500);

        assertThat(page.getTotal()).isEqualTo(250L);
        assertThat(page.getPage()).isEqualTo(3);
        assertThat(page.getSize()).isEqualTo(100);
        ArgumentCaptor<AdminCourseQuery> captor = ArgumentCaptor.forClass(AdminCourseQuery.class);
        verify(adminCourseMapper).selectCoursePage(captor.capture());
        AdminCourseQuery q = captor.getValue();
        assertThat(q.getKeyword()).isEqualTo("a\\_b\\%c");
        assertThat(q.getStatus()).isEqualTo(1);
        assertThat(q.getArchived()).isTrue();
        assertThat(q.getTeacherId()).isEqualTo(7L);
        assertThat(q.getOffset()).isEqualTo(200L);
        assertThat(q.getSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("list：参数全空 → page=1,size=20，关键字/状态/归档/教师都不筛（archived=null 即全部）")
    void list_defaults() {
        when(adminCourseMapper.countCourses(any(AdminCourseQuery.class))).thenReturn(1L);
        when(adminCourseMapper.selectCoursePage(any(AdminCourseQuery.class))).thenReturn(List.of());

        AdminPageVO<AdminCourseVO> page = service.list("   ", null, null, null, 0, null);

        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(20);
        ArgumentCaptor<AdminCourseQuery> captor = ArgumentCaptor.forClass(AdminCourseQuery.class);
        verify(adminCourseMapper).selectCoursePage(captor.capture());
        AdminCourseQuery q = captor.getValue();
        assertThat(q.getKeyword()).isNull();
        assertThat(q.getStatus()).isNull();
        assertThat(q.getArchived()).isNull();
        assertThat(q.getTeacherId()).isNull();
        assertThat(q.getOffset()).isZero();
        assertThat(q.getSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("list：计数映射——三个计数原样、null 计数归 0、archived 由 archivedAt 推出、教师名真实姓名优先回落用户名、时间为 ISO 串")
    void list_mapsRows() {
        AdminRows.CourseRow live = row(5L, "软件工程", 1, null);
        live.setTeacherRealName("王老师");
        live.setNodeCount(39L);
        live.setStudentCount(12L);
        live.setClazzCount(2L);
        AdminRows.CourseRow archived = row(6L, "旧课", 0, LocalDateTime.of(2026, 10, 1, 8, 0, 0));
        archived.setTeacherRealName("  ");
        archived.setNodeCount(null);
        archived.setStudentCount(null);
        archived.setClazzCount(null);
        when(adminCourseMapper.countCourses(any(AdminCourseQuery.class))).thenReturn(2L);
        when(adminCourseMapper.selectCoursePage(any(AdminCourseQuery.class))).thenReturn(List.of(live, archived));

        List<AdminCourseVO> records = service.list(null, null, null, null, null, null).getRecords();

        assertThat(records).hasSize(2);
        AdminCourseVO a = records.get(0);
        assertThat(a.getId()).isEqualTo(5L);
        assertThat(a.getCode()).isEqualTo("CODE5");
        assertThat(a.getName()).isEqualTo("软件工程");
        assertThat(a.getTeacherId()).isEqualTo(9L);
        assertThat(a.getTeacherName()).isEqualTo("王老师");
        assertThat(a.getStatus()).isEqualTo(1);
        assertThat(a.isArchived()).isFalse();
        assertThat(a.getArchivedAt()).isNull();
        assertThat(a.getNodeCount()).isEqualTo(39L);
        assertThat(a.getStudentCount()).isEqualTo(12L);
        assertThat(a.getClazzCount()).isEqualTo(2L);
        assertThat(a.getCreatedAt()).isEqualTo("2026-09-01T10:15:30");

        AdminCourseVO b = records.get(1);
        assertThat(b.getTeacherName()).isEqualTo("teacher9");
        assertThat(b.isArchived()).isTrue();
        assertThat(b.getArchivedAt()).isEqualTo("2026-10-01T08:00:00");
        assertThat(b.getNodeCount()).isZero();
        assertThat(b.getStudentCount()).isZero();
        assertThat(b.getClazzCount()).isZero();
    }

    @Test
    @DisplayName("list：total=0 时不再查分页数据")
    void list_emptySkipsPageQuery() {
        when(adminCourseMapper.countCourses(any(AdminCourseQuery.class))).thenReturn(0L);

        AdminPageVO<AdminCourseVO> page = service.list(null, null, false, null, null, null);

        assertThat(page.getRecords()).isEmpty();
        verify(adminCourseMapper, never()).selectCoursePage(any());
    }

    @Test
    @DisplayName("list：status 不是 0/1 → 400")
    void list_invalidStatus_400() {
        assertThatThrownBy(() -> service.list(null, 2, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.BAD_REQUEST.getCode());
        verifyNoInteractions(adminCourseMapper);
    }

    // ───────────────────────── listTeachers ─────────────────────────

    @Test
    @DisplayName("listTeachers：只查启用中的教师（role=1,status=1），映射 id/username/realName")
    void listTeachers_activeTeachersOnly() {
        SysUser t = user(9L, "teacher9", UserRole.TEACHER, 1);
        t.setRealName("王老师");
        when(userMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(t));

        List<AdminTeacherVO> out = service.listTeachers();

        assertThat(out).singleElement().satisfies(v -> {
            assertThat(v.getId()).isEqualTo(9L);
            assertThat(v.getUsername()).isEqualTo("teacher9");
            assertThat(v.getRealName()).isEqualTo("王老师");
        });
        ArgumentCaptor<QueryWrapper<SysUser>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(userMapper).selectList(captor.capture());
        QueryWrapper<SysUser> w = captor.getValue();
        assertThat(w.getSqlSegment()).contains("role").contains("status");
        assertThat(w.getParamNameValuePairs().values()).containsOnly(UserRole.TEACHER.code(), 1);
        assertThat(w.getSqlSelect()).doesNotContain("password");
    }

    // ───────────────────────── create ─────────────────────────

    @Test
    @DisplayName("create：owner=指定的启用教师、草稿状态、10 位十六进制编码；返回含计数的 VO")
    void create_forGivenTeacher() {
        when(userMapper.selectById(9L)).thenReturn(user(9L, "teacher9", UserRole.TEACHER, 1));
        when(courseMapper.selectCount(any())).thenReturn(0L);
        when(courseMapper.insert(any(Course.class))).thenAnswer(inv -> {
            ((Course) inv.getArgument(0)).setId(42L);
            return 1;
        });
        when(adminCourseMapper.selectCourseById(42L)).thenReturn(row(42L, "新课", 0, null));

        AdminCourseVO vo = service.create("  新课  ", 9L);

        ArgumentCaptor<Course> cap = ArgumentCaptor.forClass(Course.class);
        verify(courseMapper).insert(cap.capture());
        Course saved = cap.getValue();
        assertThat(saved.getName()).isEqualTo("新课");
        assertThat(saved.getTeacherId()).isEqualTo(9L);
        assertThat(saved.getStatus()).isEqualTo(0);
        assertThat(saved.getCode()).matches("[0-9A-F]{10}");
        assertThat(vo.getId()).isEqualTo(42L);
        assertThat(vo.getStatus()).isEqualTo(0);
    }

    @Test
    @DisplayName("create：教师不存在 / 已禁用 / 不是教师（学生、管理员）→ 400，不建课")
    void create_invalidTeacher_400() {
        when(userMapper.selectById(1L)).thenReturn(null);
        when(userMapper.selectById(2L)).thenReturn(user(2L, "off", UserRole.TEACHER, 0));
        when(userMapper.selectById(3L)).thenReturn(user(3L, "stu", UserRole.STUDENT, 1));
        when(userMapper.selectById(4L)).thenReturn(user(4L, "adm", UserRole.ADMIN, 1));

        for (long id : new long[]{1L, 2L, 3L, 4L}) {
            assertThatThrownBy(() -> service.create("课", id))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("启用中的教师");
        }
        verify(courseMapper, never()).insert(any(Course.class));
    }

    @Test
    @DisplayName("create：课程名为空白 → 400，不查教师")
    void create_blankName_400() {
        assertThatThrownBy(() -> service.create("   ", 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("课程名称不能为空");
        verifyNoInteractions(userMapper, courseMapper);
    }

    // ───────────────────────── transferOwner ─────────────────────────

    @Test
    @DisplayName("transferOwner：先锁课程行（含归档）再校验教师，updateById 只改 teacher_id")
    void transferOwner_locksThenUpdatesOwnerOnly() {
        when(courseAccessMapper.lockCourseIncludingArchived(5L)).thenReturn(5L);
        when(userMapper.selectById(10L)).thenReturn(user(10L, "teacher10", UserRole.TEACHER, 1));
        AdminRows.CourseRow after = row(5L, "软件工程", 1, null);
        after.setTeacherId(10L);
        after.setTeacherUsername("teacher10");
        when(adminCourseMapper.selectCourseById(5L)).thenReturn(after);

        AdminCourseVO vo = service.transferOwner(5L, 10L);

        InOrder order = inOrder(courseAccessMapper, userMapper, courseMapper);
        order.verify(courseAccessMapper).lockCourseIncludingArchived(5L);
        order.verify(userMapper).selectById(10L);
        ArgumentCaptor<Course> cap = ArgumentCaptor.forClass(Course.class);
        order.verify(courseMapper).updateById(cap.capture());
        Course patch = cap.getValue();
        assertThat(patch.getId()).isEqualTo(5L);
        assertThat(patch.getTeacherId()).isEqualTo(10L);
        assertThat(patch.getStatus()).isNull();
        assertThat(patch.getName()).isNull();
        assertThat(patch.getArchivedAt()).isNull();
        assertThat(vo.getTeacherId()).isEqualTo(10L);
        assertThat(vo.getTeacherName()).isEqualTo("teacher10");
    }

    @Test
    @DisplayName("transferOwner：转给禁用教师 / 非教师 → 400，不更新")
    void transferOwner_invalidTeacher_400() {
        when(courseAccessMapper.lockCourseIncludingArchived(5L)).thenReturn(5L);
        when(userMapper.selectById(2L)).thenReturn(user(2L, "off", UserRole.TEACHER, 0));
        when(userMapper.selectById(3L)).thenReturn(user(3L, "stu", UserRole.STUDENT, 1));

        assertThatThrownBy(() -> service.transferOwner(5L, 2L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.BAD_REQUEST.getCode());
        assertThatThrownBy(() -> service.transferOwner(5L, 3L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.BAD_REQUEST.getCode());
        verify(courseMapper, never()).updateById(any(Course.class));
    }

    @Test
    @DisplayName("transferOwner：课程不存在 → 404")
    void transferOwner_courseMissing_404() {
        when(courseAccessMapper.lockCourseIncludingArchived(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.transferOwner(99L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
        verifyNoInteractions(userMapper);
        verify(courseMapper, never()).updateById(any(Course.class));
    }

    // ───────────────────────── 发布 / 归档 / 恢复：委托 ─────────────────────────

    @Test
    @DisplayName("setPublished：委托 TeacherCourseService.setPublished，返回最新 VO")
    void setPublished_delegates() {
        when(courseMapper.selectById(5L)).thenReturn(course(5L, null));
        when(adminCourseMapper.selectCourseById(5L)).thenReturn(row(5L, "软件工程", 1, null));

        AdminCourseVO vo = service.setPublished(5L, true);

        verify(teacherCourseService).setPublished(5L, true);
        assertThat(vo.getStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("setPublished：已归档课程 → 400「请先恢复」，不委托；不存在 → 404")
    void setPublished_archivedOrMissing() {
        when(courseMapper.selectById(5L)).thenReturn(course(5L, LocalDateTime.now()));
        when(courseMapper.selectById(6L)).thenReturn(null);

        assertThatThrownBy(() -> service.setPublished(5L, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先恢复");
        assertThatThrownBy(() -> service.setPublished(6L, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ResultCode.NOT_FOUND.getCode());
        verify(teacherCourseService, never()).setPublished(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("archive：委托 TeacherCourseService.delete（软归档），返回 archived=true 的 VO")
    void archive_delegatesToDelete() {
        when(courseMapper.selectById(5L)).thenReturn(course(5L, null));
        when(adminCourseMapper.selectCourseById(5L))
                .thenReturn(row(5L, "软件工程", 0, LocalDateTime.of(2026, 10, 21, 9, 0, 0)));

        AdminCourseVO vo = service.archive(5L);

        verify(teacherCourseService).delete(5L);
        assertThat(vo.isArchived()).isTrue();
        assertThat(vo.getStatus()).isEqualTo(0);
    }

    @Test
    @DisplayName("archive：已归档 → 400，不委托")
    void archive_alreadyArchived_400() {
        when(courseMapper.selectById(5L)).thenReturn(course(5L, LocalDateTime.now()));

        assertThatThrownBy(() -> service.archive(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已归档");
        verify(teacherCourseService, never()).delete(anyLong());
    }

    @Test
    @DisplayName("restore：委托 TeacherCourseService.restore；未归档 → 400，不委托")
    void restore_delegatesOnlyWhenArchived() {
        when(courseMapper.selectById(5L)).thenReturn(course(5L, LocalDateTime.now()));
        when(courseMapper.selectById(6L)).thenReturn(course(6L, null));
        when(teacherCourseService.restore(5L)).thenReturn(new TeacherCourseVO(5L, "CODE5", "软件工程", false));
        when(adminCourseMapper.selectCourseById(5L)).thenReturn(row(5L, "软件工程", 0, null));

        AdminCourseVO vo = service.restore(5L);
        assertThat(vo.isArchived()).isFalse();
        verify(teacherCourseService).restore(5L);

        assertThatThrownBy(() -> service.restore(6L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未归档");
        verify(teacherCourseService, never()).restore(6L);
    }

    // ───────────────────────── helpers ─────────────────────────

    private static AdminRows.CourseRow row(Long id, String name, int status, LocalDateTime archivedAt) {
        AdminRows.CourseRow r = new AdminRows.CourseRow();
        r.setId(id);
        r.setCode("CODE" + id);
        r.setName(name);
        r.setTeacherId(9L);
        r.setTeacherUsername("teacher9");
        r.setStatus(status);
        r.setArchivedAt(archivedAt);
        r.setCreatedAt(LocalDateTime.of(2026, 9, 1, 10, 15, 30));
        r.setNodeCount(0L);
        r.setStudentCount(0L);
        r.setClazzCount(0L);
        return r;
    }

    private static Course course(Long id, LocalDateTime archivedAt) {
        Course c = new Course();
        c.setId(id);
        c.setTeacherId(9L);
        c.setStatus(0);
        c.setArchivedAt(archivedAt);
        return c;
    }

    private static SysUser user(Long id, String username, UserRole role, int status) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername(username);
        u.setRole(role.code());
        u.setStatus(status);
        return u;
    }
}
