package com.wenjin.controller;

import com.wenjin.common.BusinessException;
import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AdminAuthInterceptor;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.AdminCourseVO;
import com.wenjin.dto.AdminPageVO;
import com.wenjin.dto.AdminTeacherVO;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.AdminCourseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SystemAdminCourseController：契约（路径 / 参数 / 返回字段）+ 管理员路由鉴权 + 请求校验。
 */
@ExtendWith(MockitoExtension.class)
class SystemAdminCourseControllerTest {

    private static final String BASE = "/api/system-admin";

    @Mock
    private AdminCourseService courseService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SystemAdminCourseController(courseService))
                .addInterceptors(new AdminAuthInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        CurrentUser.set(new CurrentPrincipal(1L, UserRole.ADMIN));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    // ───────────────────────── GET /courses ─────────────────────────

    @Test
    @DisplayName("GET /courses：筛选参数透传，返回 {records,total,page,size} 与 AdminCourseVO 全部字段")
    void list_passesParamsAndReturnsPage() throws Exception {
        AdminPageVO<AdminCourseVO> page = new AdminPageVO<>();
        page.setRecords(List.of(vo(5L)));
        page.setTotal(21L);
        page.setPage(2);
        page.setSize(20);
        when(courseService.list("软件", 1, false, 9L, 2, 20)).thenReturn(page);

        mvc.perform(get(BASE + "/courses").param("keyword", "软件").param("status", "1")
                        .param("archived", "false").param("teacherId", "9")
                        .param("page", "2").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(21))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.records[0].id").value(5))
                .andExpect(jsonPath("$.data.records[0].code").value("52015CC4B4"))
                .andExpect(jsonPath("$.data.records[0].name").value("软件工程"))
                .andExpect(jsonPath("$.data.records[0].teacherId").value(9))
                .andExpect(jsonPath("$.data.records[0].teacherName").value("王老师"))
                .andExpect(jsonPath("$.data.records[0].status").value(1))
                .andExpect(jsonPath("$.data.records[0].archived").value(false))
                .andExpect(jsonPath("$.data.records[0].archivedAt").value((Object) null))
                .andExpect(jsonPath("$.data.records[0].nodeCount").value(39))
                .andExpect(jsonPath("$.data.records[0].studentCount").value(12))
                .andExpect(jsonPath("$.data.records[0].clazzCount").value(2))
                .andExpect(jsonPath("$.data.records[0].createdAt").value("2026-09-01T10:15:30"));
    }

    @Test
    @DisplayName("GET /courses：参数可全部省略；archived/status/teacherId 传空串等同不筛（archived 空=全部）")
    void list_optionalAndBlankParams() throws Exception {
        AdminPageVO<AdminCourseVO> page = new AdminPageVO<>();
        page.setRecords(List.of());
        page.setPage(1);
        page.setSize(20);
        when(courseService.list(null, null, null, null, null, null)).thenReturn(page);

        mvc.perform(get(BASE + "/courses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isEmpty());
        mvc.perform(get(BASE + "/courses").param("archived", "").param("status", "").param("teacherId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("GET /courses：archived=true 透传为仅归档")
    void list_archivedTrue() throws Exception {
        AdminPageVO<AdminCourseVO> page = new AdminPageVO<>();
        page.setRecords(List.of());
        when(courseService.list(null, null, true, null, null, null)).thenReturn(page);

        mvc.perform(get(BASE + "/courses").param("archived", "true"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /courses：status / teacherId 非数字 → 400")
    void list_typeMismatch_400() throws Exception {
        mvc.perform(get(BASE + "/courses").param("status", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()));
        mvc.perform(get(BASE + "/courses").param("teacherId", "x"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(courseService);
    }

    // ───────────────────────── POST /courses ─────────────────────────

    @Test
    @DisplayName("POST /courses：{name, teacherId} 透传，返回 AdminCourseVO")
    void create_returnsVo() throws Exception {
        when(courseService.create("软件工程", 9L)).thenReturn(vo(5L));

        mvc.perform(post(BASE + "/courses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"软件工程\",\"teacherId\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(5))
                .andExpect(jsonPath("$.data.teacherId").value(9));
    }

    @Test
    @DisplayName("POST /courses：缺名称 / 名称空白 / 缺 teacherId / teacherId≤0 / 名称超长 → 400，不进 service")
    void create_validation_400() throws Exception {
        String longName = "x".repeat(129);
        for (String body : List.of(
                "{\"teacherId\":9}",
                "{\"name\":\"  \",\"teacherId\":9}",
                "{\"name\":\"课\"}",
                "{\"name\":\"课\",\"teacherId\":0}",
                "{\"name\":\"" + longName + "\",\"teacherId\":9}")) {
            mvc.perform(post(BASE + "/courses").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()));
        }
        verifyNoInteractions(courseService);
    }

    @Test
    @DisplayName("POST /courses：教师无效时 service 的 400 message 原样透出")
    void create_invalidTeacher_propagates() throws Exception {
        when(courseService.create("课", 3L))
                .thenThrow(new BusinessException(ResultCode.BAD_REQUEST, "任课教师无效：须是启用中的教师"));

        mvc.perform(post(BASE + "/courses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"课\",\"teacherId\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("任课教师无效：须是启用中的教师"));
    }

    // ───────────────────────── PATCH owner / status ─────────────────────────

    @Test
    @DisplayName("PATCH /courses/{id}/owner：{teacherId} 透传，返回新 owner")
    void transferOwner() throws Exception {
        AdminCourseVO after = vo(5L);
        after.setTeacherId(10L);
        after.setTeacherName("李老师");
        when(courseService.transferOwner(5L, 10L)).thenReturn(after);

        mvc.perform(patch(BASE + "/courses/5/owner").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teacherId\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teacherId").value(10))
                .andExpect(jsonPath("$.data.teacherName").value("李老师"));
    }

    @Test
    @DisplayName("PATCH /courses/{id}/owner：缺 teacherId / 非正数 → 400；转给禁用教师的 400 原样透出")
    void transferOwner_validation() throws Exception {
        for (String body : List.of("{}", "{\"teacherId\":-1}")) {
            mvc.perform(patch(BASE + "/courses/5/owner").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        when(courseService.transferOwner(5L, 2L))
                .thenThrow(new BusinessException(ResultCode.BAD_REQUEST, "任课教师无效：须是启用中的教师"));
        mvc.perform(patch(BASE + "/courses/5/owner").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teacherId\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("任课教师无效：须是启用中的教师"));
    }

    @Test
    @DisplayName("PATCH /courses/{id}/status：{published} 透传，返回 VO；缺 published → 400")
    void setStatus() throws Exception {
        AdminCourseVO draft = vo(5L);
        draft.setStatus(0);
        when(courseService.setPublished(5L, false)).thenReturn(draft);

        mvc.perform(patch(BASE + "/courses/5/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"published\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(0));
        mvc.perform(patch(BASE + "/courses/5/status").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ───────────────────────── archive / restore ─────────────────────────

    @Test
    @DisplayName("POST /courses/{id}/archive 与 /restore：无 body，返回 VO")
    void archiveAndRestore() throws Exception {
        AdminCourseVO archived = vo(5L);
        archived.setStatus(0);
        archived.setArchived(true);
        archived.setArchivedAt("2026-10-21T09:00:00");
        when(courseService.archive(5L)).thenReturn(archived);
        when(courseService.restore(5L)).thenReturn(vo(5L));

        mvc.perform(post(BASE + "/courses/5/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.archived").value(true))
                .andExpect(jsonPath("$.data.archivedAt").value("2026-10-21T09:00:00"));
        mvc.perform(post(BASE + "/courses/5/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.archived").value(false));
    }

    @Test
    @DisplayName("POST /courses/{id}/archive：课程不存在 → 404")
    void archive_notFound() throws Exception {
        when(courseService.archive(99L)).thenThrow(new BusinessException(ResultCode.NOT_FOUND, "课程不存在"));

        mvc.perform(post(BASE + "/courses/99/archive"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("课程不存在"));
    }

    // ───────────────────────── GET /teachers ─────────────────────────

    @Test
    @DisplayName("GET /teachers：[{id, username, realName}]")
    void teachers() throws Exception {
        when(courseService.listTeachers()).thenReturn(List.of(new AdminTeacherVO(9L, "teacher9", "王老师")));

        mvc.perform(get(BASE + "/teachers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(9))
                .andExpect(jsonPath("$.data[0].username").value("teacher9"))
                .andExpect(jsonPath("$.data[0].realName").value("王老师"));
    }

    // ───────────────────────── 路由鉴权 ─────────────────────────

    @Test
    @DisplayName("教师 / 学生访问任一接口 → 403；未登录 → 401；service 不被调用")
    void nonAdmins_rejectedOnEveryEndpoint() throws Exception {
        List<RequestBuilder> requests = List.of(
                get(BASE + "/courses"),
                post(BASE + "/courses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"课\",\"teacherId\":9}"),
                patch(BASE + "/courses/5/owner").contentType(MediaType.APPLICATION_JSON).content("{\"teacherId\":9}"),
                patch(BASE + "/courses/5/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"published\":true}"),
                post(BASE + "/courses/5/archive"),
                post(BASE + "/courses/5/restore"),
                get(BASE + "/teachers"));

        CurrentUser.set(new CurrentPrincipal(2L, UserRole.TEACHER));
        for (RequestBuilder request : requests) {
            mvc.perform(request).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
        }
        CurrentUser.set(new CurrentPrincipal(3L, UserRole.STUDENT));
        for (RequestBuilder request : requests) {
            mvc.perform(request).andExpect(status().isForbidden());
        }
        CurrentUser.clear();
        for (RequestBuilder request : requests) {
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
        }

        verifyNoInteractions(courseService);
    }

    // ───────────────────────── helpers ─────────────────────────

    private static AdminCourseVO vo(Long id) {
        AdminCourseVO v = new AdminCourseVO();
        v.setId(id);
        v.setCode("52015CC4B4");
        v.setName("软件工程");
        v.setTeacherId(9L);
        v.setTeacherName("王老师");
        v.setStatus(1);
        v.setArchived(false);
        v.setArchivedAt(null);
        v.setNodeCount(39L);
        v.setStudentCount(12L);
        v.setClazzCount(2L);
        v.setCreatedAt("2026-09-01T10:15:30");
        return v;
    }
}
