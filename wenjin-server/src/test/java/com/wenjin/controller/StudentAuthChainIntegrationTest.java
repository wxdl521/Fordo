package com.wenjin.controller;

import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AuthContextInterceptor;
import com.wenjin.config.CurrentUser;
import com.wenjin.security.TokenService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 学生侧鉴权"全链路"集成测试（standalone MockMvc）：把真实的 {@link AuthContextInterceptor}
 * + 真实 {@code AccessGuard} + 真实 Controller + 真实 {@link GlobalExceptionHandler} 串起来，
 * 用真实 HTTP 请求验证 {@code Authorization: Bearer <token>} → CurrentUser → assertSelf 的端到端行为。
 *
 * <p>这是单测（mock 掉 CurrentUser）覆盖不到的层：它能抓住"请求没带 Authorization 头就放行"这类回归
 * （正是 SSE 原生 fetch 漏发 Authorization 头导致 401 的同一类问题在后端侧的护栏）。
 * 下游 service 用 Mockito mock（无 DB）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudentAuthChainIntegrationTest {

    @Mock DiagnosticService diagnosticService;
    @Mock DiagnosticResultService diagnosticResultService;
    @Mock CourseService courseService;

    private final TokenService tokenService = new TokenService("test-secret", 3600);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        DiagnosticController controller =
                new DiagnosticController(diagnosticService, diagnosticResultService, courseService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new AuthContextInterceptor(tokenService))
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
        // 不带 Authorization 头：拦截器不填 CurrentUser → assertSelf 抛 UNAUTHORIZED
        mvc.perform(get("/api/diagnostic/result").param("studentId", "2").param("courseId", "5"))
                .andExpect(status().isOk()) // 业务异常仍走 HTTP 200 + code 区分
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void getResult_forgedToken_returns401() throws Exception {
        mvc.perform(get("/api/diagnostic/result")
                        .header("Authorization", "Bearer forged.token.value")
                        .param("studentId", "2").param("courseId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void getResult_otherUsersStudentId_returns403() throws Exception {
        // 当前用户 9，却查 studentId=2 的学情 → FORBIDDEN
        mvc.perform(get("/api/diagnostic/result")
                        .header("Authorization", "Bearer " + tokenService.issue(9L, 2))
                        .param("studentId", "2").param("courseId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    void getResult_ownStudentId_passes() throws Exception {
        // 当前用户 2 查自己的学情 → 放行（code=0）
        mvc.perform(get("/api/diagnostic/result")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .param("studentId", "2").param("courseId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ── POST /api/diagnostic/submit（body 携带 studentId，写端点）──

    @Test
    void submit_noAuthHeader_returns401() throws Exception {
        // 写端点同样护住：无 Authorization 头不得替他人交卷
        mvc.perform(post("/api/diagnostic/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5,\"answers\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void submit_ownStudentId_passes() throws Exception {
        mvc.perform(post("/api/diagnostic/submit")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5,\"answers\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
