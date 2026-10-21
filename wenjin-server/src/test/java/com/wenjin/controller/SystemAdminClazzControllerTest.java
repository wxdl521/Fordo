package com.wenjin.controller;

import com.wenjin.common.BusinessException;
import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AdminAuthInterceptor;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.AdminPageVO;
import com.wenjin.dto.ClazzAddStudentsResultVO;
import com.wenjin.dto.ClazzCourseEnrollVO;
import com.wenjin.dto.ClazzCourseVO;
import com.wenjin.dto.ClazzDetailVO;
import com.wenjin.dto.ClazzStudentVO;
import com.wenjin.dto.ClazzVO;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.AdminClazzService;
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

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SystemAdminClazzController：契约（路径 / 参数 / 返回字段）+ 管理员路由鉴权 + 请求校验。
 */
@ExtendWith(MockitoExtension.class)
class SystemAdminClazzControllerTest {

    private static final String BASE = "/api/system-admin/clazzes";

    @Mock
    private AdminClazzService clazzService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SystemAdminClazzController(clazzService))
                .addInterceptors(new AdminAuthInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        CurrentUser.set(new CurrentPrincipal(1L, UserRole.ADMIN));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    // ───────────────────────── 列表 / 详情 ─────────────────────────

    @Test
    @DisplayName("GET /clazzes：参数透传，返回 {records,total,page,size} 与 ClazzVO 全部字段")
    void list() throws Exception {
        AdminPageVO<ClazzVO> page = new AdminPageVO<>();
        page.setRecords(List.of(vo(100L, "软工2201")));
        page.setTotal(1L);
        page.setPage(1);
        page.setSize(20);
        when(clazzService.list("软工", 1, 20)).thenReturn(page);

        mvc.perform(get(BASE).param("keyword", "软工").param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.records[0].id").value(100))
                .andExpect(jsonPath("$.data.records[0].name").value("软工2201"))
                .andExpect(jsonPath("$.data.records[0].description").value("2022 级"))
                .andExpect(jsonPath("$.data.records[0].studentCount").value(30))
                .andExpect(jsonPath("$.data.records[0].courseCount").value(2))
                .andExpect(jsonPath("$.data.records[0].createdAt").value("2026-09-01T08:30:00"));
    }

    @Test
    @DisplayName("GET /clazzes：参数可省略；page 非数字 → 400")
    void list_optionalAndTypeMismatch() throws Exception {
        AdminPageVO<ClazzVO> page = new AdminPageVO<>();
        page.setRecords(List.of());
        when(clazzService.list(null, null, null)).thenReturn(page);

        mvc.perform(get(BASE)).andExpect(status().isOk()).andExpect(jsonPath("$.data.records").isEmpty());
        mvc.perform(get(BASE).param("page", "x")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /clazzes/{id}：ClazzVO 字段 + students[{id,username,realName,status}] + courses[{id,name,status,archived}]")
    void detail() throws Exception {
        ClazzDetailVO d = new ClazzDetailVO();
        d.setId(100L);
        d.setName("软工2201");
        d.setDescription("2022 级");
        d.setStudentCount(1L);
        d.setCourseCount(1L);
        d.setCreatedAt("2026-09-01T08:30:00");
        ClazzStudentVO s = new ClazzStudentVO();
        s.setId(3L);
        s.setUsername("s1");
        s.setRealName("学生一");
        s.setStatus(1);
        d.getStudents().add(s);
        ClazzCourseVO c = new ClazzCourseVO();
        c.setId(5L);
        c.setName("软件工程");
        c.setStatus(0);
        c.setArchived(true);
        d.getCourses().add(c);
        when(clazzService.detail(100L)).thenReturn(d);

        mvc.perform(get(BASE + "/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.name").value("软工2201"))
                .andExpect(jsonPath("$.data.studentCount").value(1))
                .andExpect(jsonPath("$.data.courseCount").value(1))
                .andExpect(jsonPath("$.data.createdAt").value("2026-09-01T08:30:00"))
                .andExpect(jsonPath("$.data.students[0].id").value(3))
                .andExpect(jsonPath("$.data.students[0].username").value("s1"))
                .andExpect(jsonPath("$.data.students[0].realName").value("学生一"))
                .andExpect(jsonPath("$.data.students[0].status").value(1))
                .andExpect(jsonPath("$.data.courses[0].id").value(5))
                .andExpect(jsonPath("$.data.courses[0].name").value("软件工程"))
                .andExpect(jsonPath("$.data.courses[0].status").value(0))
                .andExpect(jsonPath("$.data.courses[0].archived").value(true));
    }

    @Test
    @DisplayName("GET /clazzes/{id}：不存在 → 404")
    void detail_notFound() throws Exception {
        when(clazzService.detail(404L)).thenThrow(new BusinessException(ResultCode.NOT_FOUND, "班级不存在"));

        mvc.perform(get(BASE + "/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("班级不存在"));
    }

    // ───────────────────────── 新建 / 修改 / 删除 ─────────────────────────

    @Test
    @DisplayName("POST /clazzes：{name, description?} 透传，返回 ClazzVO；description 可省略")
    void create() throws Exception {
        when(clazzService.create("软工2201", "2022 级")).thenReturn(vo(100L, "软工2201"));
        when(clazzService.create("软工2202", null)).thenReturn(vo(101L, "软工2202"));

        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"软工2201\",\"description\":\"2022 级\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(100));
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"软工2202\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(101));
    }

    @Test
    @DisplayName("POST /clazzes：名称缺失 / 空白 / 超 64 字、说明超 255 字 → 400，不进 service")
    void create_validation_400() throws Exception {
        for (String body : List.of(
                "{}",
                "{\"name\":\"   \"}",
                "{\"name\":\"" + "x".repeat(65) + "\"}",
                "{\"name\":\"ok\",\"description\":\"" + "d".repeat(256) + "\"}")) {
            mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()));
        }
        verifyNoInteractions(clazzService);
    }

    @Test
    @DisplayName("POST /clazzes：班级名重复 → 400「班级名已存在」")
    void create_duplicateName_400() throws Exception {
        when(clazzService.create("软工2201", null))
                .thenThrow(new BusinessException(ResultCode.BAD_REQUEST, "班级名已存在"));

        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"软工2201\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("班级名已存在"));
    }

    @Test
    @DisplayName("PUT /clazzes/{id}：透传 id/name/description，返回 ClazzVO")
    void update() throws Exception {
        when(clazzService.update(100L, "软工2201A", null)).thenReturn(vo(100L, "软工2201A"));

        mvc.perform(put(BASE + "/100").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"软工2201A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("软工2201A"));
    }

    @Test
    @DisplayName("DELETE /clazzes/{id}：data=null")
    void deleteClazz() throws Exception {
        mvc.perform(delete(BASE + "/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value((Object) null));
        verify(clazzService).delete(100L);
    }

    // ───────────────────────── 成员 ─────────────────────────

    @Test
    @DisplayName("POST /clazzes/{id}/students：usernames 透传；返回 {added, movedFrom[{studentId,fromClazzName}], notFound, enrolled}")
    void addStudents_byUsernames() throws Exception {
        ClazzAddStudentsResultVO result = new ClazzAddStudentsResultVO();
        result.setAdded(2);
        result.getMovedFrom().add(new ClazzAddStudentsResultVO.MovedFrom(4L, "软工2202"));
        result.getNotFound().add("ghost");
        result.setEnrolled(3);
        when(clazzService.addStudents(eq(100L), isNull(),
                eq(List.of("s1", "s2", "ghost")))).thenReturn(result);

        mvc.perform(post(BASE + "/100/students").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernames\":[\"s1\",\"s2\",\"ghost\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.added").value(2))
                .andExpect(jsonPath("$.data.movedFrom[0].studentId").value(4))
                .andExpect(jsonPath("$.data.movedFrom[0].fromClazzName").value("软工2202"))
                .andExpect(jsonPath("$.data.notFound[0]").value("ghost"))
                .andExpect(jsonPath("$.data.enrolled").value(3));
    }

    @Test
    @DisplayName("POST /clazzes/{id}/students：studentIds 透传")
    void addStudents_byIds() throws Exception {
        ClazzAddStudentsResultVO result = new ClazzAddStudentsResultVO();
        result.setAdded(2);
        when(clazzService.addStudents(eq(100L),
                eq(List.of(3L, 4L)), isNull())).thenReturn(result);

        mvc.perform(post(BASE + "/100/students").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentIds\":[3,4]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.added").value(2))
                .andExpect(jsonPath("$.data.movedFrom").isEmpty())
                .andExpect(jsonPath("$.data.notFound").isEmpty());
    }

    @Test
    @DisplayName("POST /clazzes/{id}/students：超过 500 个 → 400，不进 service")
    void addStudents_overLimit_400() throws Exception {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            names.add("\"u" + i + "\"");
        }
        mvc.perform(post(BASE + "/100/students").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernames\":[" + String.join(",", names) + "]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(clazzService);
    }

    @Test
    @DisplayName("DELETE /clazzes/{id}/students/{studentId}：透传两个 id，data=null")
    void removeStudent() throws Exception {
        mvc.perform(delete(BASE + "/100/students/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value((Object) null));
        verify(clazzService).removeStudent(100L, 3L);
    }

    // ───────────────────────── 课程 ─────────────────────────

    @Test
    @DisplayName("POST /clazzes/{id}/courses：{courseId} → {enrolled}")
    void addCourse() throws Exception {
        when(clazzService.addCourse(100L, 5L)).thenReturn(new ClazzCourseEnrollVO(28));

        mvc.perform(post(BASE + "/100/courses").contentType(MediaType.APPLICATION_JSON).content("{\"courseId\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enrolled").value(28));
    }

    @Test
    @DisplayName("POST /clazzes/{id}/courses：缺 courseId / 非正数 → 400")
    void addCourse_validation_400() throws Exception {
        for (String body : List.of("{}", "{\"courseId\":0}")) {
            mvc.perform(post(BASE + "/100/courses").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(clazzService);
    }

    @Test
    @DisplayName("DELETE /clazzes/{id}/courses/{courseId}：透传两个 id，data=null")
    void removeCourse() throws Exception {
        mvc.perform(delete(BASE + "/100/courses/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value((Object) null));
        verify(clazzService).removeCourse(100L, 5L);
    }

    // ───────────────────────── 路由鉴权 ─────────────────────────

    @Test
    @DisplayName("教师 / 学生访问任一接口 → 403；未登录 → 401；service 不被调用")
    void nonAdmins_rejectedOnEveryEndpoint() throws Exception {
        List<RequestBuilder> requests = List.of(
                get(BASE),
                post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"),
                put(BASE + "/100").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"),
                delete(BASE + "/100"),
                get(BASE + "/100"),
                post(BASE + "/100/students").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernames\":[\"s1\"]}"),
                delete(BASE + "/100/students/3"),
                post(BASE + "/100/courses").contentType(MediaType.APPLICATION_JSON).content("{\"courseId\":5}"),
                delete(BASE + "/100/courses/5"));

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

        verifyNoInteractions(clazzService);
    }

    // ───────────────────────── helpers ─────────────────────────

    private static ClazzVO vo(Long id, String name) {
        ClazzVO v = new ClazzVO();
        v.setId(id);
        v.setName(name);
        v.setDescription("2022 级");
        v.setStudentCount(30L);
        v.setCourseCount(2L);
        v.setCreatedAt("2026-09-01T08:30:00");
        return v;
    }
}
