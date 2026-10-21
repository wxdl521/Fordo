package com.wenjin.controller;

import com.wenjin.common.BusinessException;
import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AdminAuthInterceptor;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.AdminCreateUserRequest;
import com.wenjin.dto.AdminResetPasswordVO;
import com.wenjin.dto.AdminUpdateUserRequest;
import com.wenjin.dto.AdminUserImportResultVO;
import com.wenjin.dto.AdminUserPageVO;
import com.wenjin.dto.AdminUserVO;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.AdminUserImportService;
import com.wenjin.service.AdminUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SystemAdminUserManageController：契约（路径/参数/返回字段）+ 管理员路由鉴权 + 请求校验。
 */
@ExtendWith(MockitoExtension.class)
class SystemAdminUserManageControllerTest {

    private static final String BASE = "/api/system-admin/users";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Mock
    private AdminUserService userService;
    @Mock
    private AdminUserImportService importService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SystemAdminUserManageController(userService, importService))
                .addInterceptors(new AdminAuthInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        CurrentUser.set(new CurrentPrincipal(1L, UserRole.ADMIN));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    // ───────────────────────── GET /users ─────────────────────────

    @Test
    @DisplayName("GET /users：参数透传，返回 {records,total,page,size}")
    void list_passesParamsAndReturnsPage() throws Exception {
        AdminUserPageVO page = new AdminUserPageVO();
        page.setRecords(List.of(vo(5L, "alice", 2, 1)));
        page.setTotal(41L);
        page.setPage(2);
        page.setSize(20);
        when(userService.list("ali", 2, 1, 2, 20)).thenReturn(page);

        mvc.perform(get(BASE).param("keyword", "ali").param("role", "2").param("status", "1")
                        .param("page", "2").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(41))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.records[0].id").value(5))
                .andExpect(jsonPath("$.data.records[0].username").value("alice"))
                .andExpect(jsonPath("$.data.records[0].realName").value("User alice"))
                .andExpect(jsonPath("$.data.records[0].role").value(2))
                .andExpect(jsonPath("$.data.records[0].status").value(1))
                .andExpect(jsonPath("$.data.records[0].createdAt").value("2026-10-21T10:15:30"))
                .andExpect(jsonPath("$.data.records[0].password").doesNotExist());
    }

    @Test
    @DisplayName("GET /users：全部参数可省略")
    void list_allParamsOptional() throws Exception {
        AdminUserPageVO page = new AdminUserPageVO();
        page.setRecords(List.of());
        page.setPage(1);
        page.setSize(20);
        when(userService.list(null, null, null, null, null)).thenReturn(page);

        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("GET /users：role 非数字 → 400")
    void list_typeMismatch_400() throws Exception {
        mvc.perform(get(BASE).param("role", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()));
        verifyNoInteractions(userService);
    }

    // ───────────────────────── POST /users ─────────────────────────

    @Test
    @DisplayName("POST /users：返回 AdminUserVO")
    void create_returnsVo() throws Exception {
        when(userService.create(any(AdminCreateUserRequest.class))).thenReturn(vo(9L, "newone", 1, 1));

        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newone\",\"password\":\"Passw0rd!\",\"realName\":\"新人\",\"role\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.username").value("newone"))
                .andExpect(jsonPath("$.data.role").value(1));

        ArgumentCaptor<AdminCreateUserRequest> captor = ArgumentCaptor.forClass(AdminCreateUserRequest.class);
        verify(userService).create(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("newone");
        assertThat(captor.getValue().getRealName()).isEqualTo("新人");
        assertThat(captor.getValue().getRole()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /users：缺字段 / 用户名过短 / 密码过短 → 400，不进 service")
    void create_validation_400() throws Exception {
        for (String body : List.of(
                "{\"username\":\"ab\",\"password\":\"Passw0rd!\",\"role\":2}",
                "{\"username\":\"someone\",\"password\":\"short\",\"role\":2}",
                "{\"username\":\"someone\",\"password\":\"Passw0rd!\"}",
                "{\"password\":\"Passw0rd!\",\"role\":2}")) {
            mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()));
        }
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("POST /users：重名时 service 抛 USER_EXISTS → HTTP 409 + message")
    void create_duplicate_409() throws Exception {
        when(userService.create(any(AdminCreateUserRequest.class)))
                .thenThrow(new BusinessException(ResultCode.USER_EXISTS, "用户名已存在"));

        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"taken\",\"password\":\"Passw0rd!\",\"role\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ResultCode.USER_EXISTS.getCode()))
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    // ───────────────────────── PUT /users/{id} ─────────────────────────

    @Test
    @DisplayName("PUT /users/{id}：改姓名")
    void update_realName() throws Exception {
        AdminUserVO updated = vo(5L, "alice", 2, 1);
        updated.setRealName("爱丽丝");
        when(userService.updateRealName(eq(5L), any(AdminUpdateUserRequest.class))).thenReturn(updated);

        mvc.perform(put(BASE + "/5").contentType(MediaType.APPLICATION_JSON).content("{\"realName\":\"爱丽丝\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.realName").value("爱丽丝"));

        ArgumentCaptor<AdminUpdateUserRequest> captor = ArgumentCaptor.forClass(AdminUpdateUserRequest.class);
        verify(userService).updateRealName(eq(5L), captor.capture());
        assertThat(captor.getValue().getRealName()).isEqualTo("爱丽丝");
    }

    @Test
    @DisplayName("PUT /users/{id}：姓名为空 → 400")
    void update_blank_400() throws Exception {
        mvc.perform(put(BASE + "/5").contentType(MediaType.APPLICATION_JSON).content("{\"realName\":\"  \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(userService);
    }

    // ───────────────────────── PATCH /users/{id}/status ─────────────────────────

    @Test
    @DisplayName("PATCH /users/{id}/status：status 透传给 service")
    void changeStatus() throws Exception {
        when(userService.changeStatus(6L, 0)).thenReturn(vo(6L, "bob", 2, 0));

        mvc.perform(patch(BASE + "/6/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(6))
                .andExpect(jsonPath("$.data.status").value(0));
    }

    @Test
    @DisplayName("PATCH /users/{id}/status：status 缺失或不在 {0,1} → 400")
    void changeStatus_validation_400() throws Exception {
        for (String body : List.of("{}", "{\"status\":2}", "{\"status\":-1}")) {
            mvc.perform(patch(BASE + "/6/status").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("PATCH /users/{id}/status：service 拒绝禁用自己 → 400 + message 原样透出")
    void changeStatus_businessError_propagates() throws Exception {
        when(userService.changeStatus(1L, 0))
                .thenThrow(new BusinessException(ResultCode.BAD_REQUEST, "不能禁用自己"));

        mvc.perform(patch(BASE + "/1/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("不能禁用自己"));
    }

    // ───────────────────────── POST /users/{id}/reset-password ─────────────────────────

    @Test
    @DisplayName("POST reset-password：无 body → 服务端生成，data.password 为明文")
    void resetPassword_noBody_generated() throws Exception {
        when(userService.resetPassword(7L, null)).thenReturn(new AdminResetPasswordVO("Abcd2345Efgh"));

        mvc.perform(post(BASE + "/7/reset-password"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.password").value("Abcd2345Efgh"));
    }

    @Test
    @DisplayName("POST reset-password：指定 newPassword → data.password=null")
    void resetPassword_specified() throws Exception {
        when(userService.resetPassword(7L, "NewPass#123")).thenReturn(new AdminResetPasswordVO(null));

        mvc.perform(post(BASE + "/7/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"NewPass#123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.password").value((Object) null));
    }

    @Test
    @DisplayName("POST reset-password：body 为 {} 或 newPassword=null 同样走生成")
    void resetPassword_emptyJson() throws Exception {
        when(userService.resetPassword(eq(7L), isNull())).thenReturn(new AdminResetPasswordVO("Zxcv5678Bnml"));

        mvc.perform(post(BASE + "/7/reset-password").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.password").value("Zxcv5678Bnml"));
        mvc.perform(post(BASE + "/7/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.password").value("Zxcv5678Bnml"));
    }

    // ───────────────────────── import-template / import ─────────────────────────

    @Test
    @DisplayName("GET /users/import-template：xlsx 附件下载")
    void importTemplate_downloadsXlsx() throws Exception {
        byte[] bytes = {0x50, 0x4B, 0x03, 0x04, 1, 2, 3};
        when(importService.buildTemplate()).thenReturn(bytes);

        mvc.perform(get(BASE + "/import-template").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(content().bytes(bytes));
    }

    @Test
    @DisplayName("POST /users/import：multipart file → {created, errors[{row,username,message}]}")
    void importUsers_returnsResult() throws Exception {
        AdminUserImportResultVO result = new AdminUserImportResultVO();
        result.setCreated(0);
        result.getErrors().add(new AdminUserImportResultVO.RowError(3, "ab", "用户名长度须在 3-64 之间"));
        when(importService.importUsers(any())).thenReturn(result);
        MockMultipartFile file = new MockMultipartFile("file", "users.xlsx", XLSX, new byte[]{1, 2, 3});

        mvc.perform(multipart(BASE + "/import").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.created").value(0))
                .andExpect(jsonPath("$.data.errors[0].row").value(3))
                .andExpect(jsonPath("$.data.errors[0].username").value("ab"))
                .andExpect(jsonPath("$.data.errors[0].message").value("用户名长度须在 3-64 之间"));

        verify(importService).importUsers(any());
    }

    @Test
    @DisplayName("POST /users/import：缺少 file 部分 → 400（由 service 报错，而非 500）")
    void importUsers_missingFile_400() throws Exception {
        when(importService.importUsers(isNull()))
                .thenThrow(new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空"));

        mvc.perform(multipart(BASE + "/import"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("上传文件不能为空"));
    }

    // ───────────────────────── 路由鉴权 ─────────────────────────

    @Test
    @DisplayName("教师 / 学生访问任一接口 → 403；未登录 → 401；service 不被调用")
    void nonAdmins_rejectedOnEveryEndpoint() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "users.xlsx", XLSX, new byte[]{1});
        List<org.springframework.test.web.servlet.RequestBuilder> requests = List.of(
                get(BASE),
                post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"someone\",\"password\":\"Passw0rd!\",\"role\":2}"),
                put(BASE + "/5").contentType(MediaType.APPLICATION_JSON).content("{\"realName\":\"x\"}"),
                patch(BASE + "/5/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":0}"),
                post(BASE + "/5/reset-password"),
                get(BASE + "/import-template"),
                multipart(BASE + "/import").file(file));

        CurrentUser.set(new CurrentPrincipal(2L, UserRole.TEACHER));
        for (var request : requests) {
            mvc.perform(request).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
        }
        CurrentUser.set(new CurrentPrincipal(3L, UserRole.STUDENT));
        for (var request : requests) {
            mvc.perform(request).andExpect(status().isForbidden());
        }
        CurrentUser.clear();
        for (var request : requests) {
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
        }

        verifyNoInteractions(userService, importService);
    }

    // ───────────────────────── helpers ─────────────────────────

    private static AdminUserVO vo(Long id, String username, int role, int status) {
        AdminUserVO v = new AdminUserVO();
        v.setId(id);
        v.setUsername(username);
        v.setRealName("User " + username);
        v.setRole(role);
        v.setStatus(status);
        v.setCreatedAt("2026-10-21T10:15:30");
        return v;
    }
}
