package com.wenjin.controller;

import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AuthContextInterceptor;
import com.wenjin.config.CurrentUser;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.PrincipalLoader;
import com.wenjin.security.TokenService;
import com.wenjin.security.UserRole;
import com.wenjin.service.CourseService;
import com.wenjin.service.DiagnosticResultService;
import com.wenjin.service.DiagnosticService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 学生侧鉴权"全链路"集成测试（standalone MockMvc）：把真实的 {@link AuthContextInterceptor}
 * + 真实 {@code AccessGuard} + 真实 Controller + 真实 {@link GlobalExceptionHandler} 串起来，
 * 用真实 HTTP 请求验证 {@code Authorization: Bearer <token>} → CurrentUser → principal 派生身份 的端到端行为。
 *
 * <p>下游 service 用 Mockito mock（无 DB）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudentAuthChainIntegrationTest {

    @Mock DiagnosticService diagnosticService;
    @Mock DiagnosticResultService diagnosticResultService;
    @Mock CourseService courseService;
    @Mock PrincipalLoader principalLoader;

    private final TokenService tokenService = new TokenService("test-secret", 3600);
    private MockMvc diagnosticMvc;
    private MockMvc courseMvc;

    @BeforeEach
    void setup() {
        // AuthContextInterceptor 走带令牌签发时间的重载
        when(principalLoader.load(anyLong(), anyLong())).thenAnswer(inv -> {
            long uid = inv.getArgument(0);
            // uid=8 模拟教师；其余默认学生
            UserRole role = uid == 8L ? UserRole.TEACHER : UserRole.STUDENT;
            return Optional.of(new CurrentPrincipal(uid, role));
        });

        AuthContextInterceptor auth = new AuthContextInterceptor(tokenService, principalLoader);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        DiagnosticController diagnosticController =
                new DiagnosticController(diagnosticService, diagnosticResultService, courseService);
        diagnosticMvc = MockMvcBuilders.standaloneSetup(diagnosticController)
                .addInterceptors(auth)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        CourseController courseController = new CourseController(courseService);
        courseMvc = MockMvcBuilders.standaloneSetup(courseController)
                .addInterceptors(auth)
                .setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearCurrentUser() {
        CurrentUser.clear();
    }

    // ── GET /api/diagnostic/result（param 携带 studentId）──

    @Test
    void getResult_noAuthHeader_returns401() throws Exception {
        diagnosticMvc.perform(get("/api/diagnostic/result").param("studentId", "2").param("courseId", "5"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void getResult_forgedToken_returns401() throws Exception {
        diagnosticMvc.perform(get("/api/diagnostic/result")
                        .header("Authorization", "Bearer forged.token.value")
                        .param("studentId", "2").param("courseId", "5"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void getResult_otherUsersStudentId_returns403() throws Exception {
        // 当前用户 9，却查 studentId=2 的学情 → FORBIDDEN
        diagnosticMvc.perform(get("/api/diagnostic/result")
                        .header("Authorization", "Bearer " + tokenService.issue(9L, 2))
                        .param("studentId", "2").param("courseId", "5"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    void getResult_ownStudentId_passes() throws Exception {
        diagnosticMvc.perform(get("/api/diagnostic/result")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .param("studentId", "2").param("courseId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void getResult_omittedStudentId_usesPrincipal() throws Exception {
        // 兼容窗口后前端不再发送 studentId：身份从 principal 派生
        diagnosticMvc.perform(get("/api/diagnostic/result")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .param("courseId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(diagnosticResultService).getResult(2L, 5L);
    }

    // ── POST /api/diagnostic/submit（body 携带 studentId，写端点）──

    @Test
    void submit_noAuthHeader_returns401() throws Exception {
        diagnosticMvc.perform(post("/api/diagnostic/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5,\"answers\":[{\"questionId\":1,\"optionKey\":\"A\"}]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void submit_ownStudentId_passes() throws Exception {
        diagnosticMvc.perform(post("/api/diagnostic/submit")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5,\"answers\":[{\"questionId\":1,\"optionKey\":\"A\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void submit_omittedStudentId_usesPrincipal() throws Exception {
        diagnosticMvc.perform(post("/api/diagnostic/submit")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":5,\"answers\":[{\"questionId\":1,\"optionKey\":\"A\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ── POST /api/course/enroll 矩阵 ──

    @Test
    void enroll_noAuthHeader_returns401() throws Exception {
        courseMvc.perform(post("/api/course/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":5}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
        verify(courseService, never()).enroll(anyLong(), anyLong());
    }

    @Test
    void enroll_legacyStudentIdForOtherUser_isIgnored_enrollsPrincipal() throws Exception {
        // 请求体遗留 studentId=B 被 Jackson 忽略（DTO 无该字段）；A 只能给 A 选课
        courseMvc.perform(post("/api/course/enroll")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":9,\"courseId\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(courseService).enroll(eq(2L), eq(5L));
        verify(courseService, never()).enroll(eq(9L), anyLong());
    }

    @Test
    void enroll_studentOnlyCourseId_enrollsSelf() throws Exception {
        courseMvc.perform(post("/api/course/enroll")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(courseService).enroll(2L, 5L);
    }

    @Test
    void enroll_teacherRole_returns403() throws Exception {
        // uid=8 由 PrincipalLoader 映射为教师
        courseMvc.perform(post("/api/course/enroll")
                        .header("Authorization", "Bearer " + tokenService.issue(8L, 1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
        verify(courseService, never()).enroll(anyLong(), anyLong());
    }

    @Test
    void getMyCourses_legacyStudentIdMismatch_returns403() throws Exception {
        courseMvc.perform(get("/api/course/my")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .param("studentId", "9"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
        verify(courseService, never()).getMyCourses(anyLong());
    }

    @Test
    void getMyCourses_omittedStudentId_usesPrincipal() throws Exception {
        courseMvc.perform(get("/api/course/my")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(courseService).getMyCourses(2L);
    }
}
