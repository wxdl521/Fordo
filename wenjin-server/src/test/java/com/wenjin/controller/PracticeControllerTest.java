package com.wenjin.controller;

import com.wenjin.common.BusinessException;
import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AuthContextInterceptor;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.PracticeHistoryVO;
import com.wenjin.dto.PracticeStartVO;
import com.wenjin.dto.PracticeSubmitVO;
import com.wenjin.security.TokenService;
import com.wenjin.service.CourseService;
import com.wenjin.service.PracticeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PracticeController 集成测试（standalone MockMvc）。
 *
 * <p>串联真实 {@link AuthContextInterceptor}、真实 {@link com.wenjin.config.AccessGuard}、
 * 真实 Controller、真实 {@link GlobalExceptionHandler}，验证：
 * <ul>
 *   <li>三个端点均要求有效 Bearer 令牌（无 token → 401，伪造 token → 401）</li>
 *   <li>三个端点均要求 studentId 归属当前用户（其他用户的 studentId → 403）</li>
 *   <li>他人 sessionId 提交 → 403（service 层抛，Controller 正确透传）</li>
 *   <li>start 端点：size=0 或负数 → 400（T3 遗留入参收紧）</li>
 *   <li>submit 端点：重复 questionId → 400（T4 遗留入参收紧）</li>
 *   <li>合法请求正常放行（code=0）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PracticeControllerTest {

    @Mock PracticeService practiceService;
    @Mock CourseService courseService;

    private final TokenService tokenService = new TokenService("test-secret", 3600);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        PracticeController controller = new PracticeController(practiceService, courseService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new AuthContextInterceptor(tokenService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearCurrentUser() {
        CurrentUser.clear();
    }

    // ══════════════════════════════════════════════════════════════════════
    // POST /api/practice/start — assertSelf
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("start: 无 Authorization 头 → 401")
    void start_noToken_returns401() throws Exception {
        mvc.perform(post("/api/practice/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5,\"nodeId\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("start: 伪造 token → 401")
    void start_forgedToken_returns401() throws Exception {
        mvc.perform(post("/api/practice/start")
                        .header("Authorization", "Bearer forged.token.here")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5,\"nodeId\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("start: token 归属他人（uid=9，studentId=2）→ 403")
    void start_otherUserToken_returns403() throws Exception {
        mvc.perform(post("/api/practice/start")
                        .header("Authorization", "Bearer " + tokenService.issue(9L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5,\"nodeId\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    @DisplayName("start: 合法 token + 本人 studentId → code=0")
    void start_ownUser_passes() throws Exception {
        when(practiceService.start(any(), any(), any(), any())).thenReturn(new PracticeStartVO());
        mvc.perform(post("/api/practice/start")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5,\"nodeId\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ── start 入参校验（T3 遗留修复）──

    @Test
    @DisplayName("start: size=0 → 400（下界收紧）")
    void start_sizeZero_returns400() throws Exception {
        mvc.perform(post("/api/practice/start")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5,\"nodeId\":10,\"size\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()));
    }

    @Test
    @DisplayName("start: size=-3 → 400（下界收紧）")
    void start_sizeNegative_returns400() throws Exception {
        mvc.perform(post("/api/practice/start")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5,\"nodeId\":10,\"size\":-3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()));
    }

    @Test
    @DisplayName("start: size=1（正整数）→ 允许，不抛 400")
    void start_sizeOne_passes() throws Exception {
        when(practiceService.start(any(), any(), any(), any())).thenReturn(new PracticeStartVO());
        mvc.perform(post("/api/practice/start")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5,\"nodeId\":10,\"size\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ══════════════════════════════════════════════════════════════════════
    // POST /api/practice/{sessionId}/submit — assertSelf + 归属校验
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("submit: 无 Authorization 头 → 401")
    void submit_noToken_returns401() throws Exception {
        mvc.perform(post("/api/practice/100/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"answers\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("submit: token 归属他人（uid=9，studentId=2）→ 403")
    void submit_otherUserToken_returns403() throws Exception {
        mvc.perform(post("/api/practice/100/submit")
                        .header("Authorization", "Bearer " + tokenService.issue(9L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"answers\":[{\"questionId\":1,\"studentAnswer\":\"A\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    @DisplayName("submit: 合法 token + 本人 studentId → code=0")
    void submit_ownUser_passes() throws Exception {
        when(practiceService.submit(any(), any())).thenReturn(new PracticeSubmitVO());
        mvc.perform(post("/api/practice/100/submit")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"answers\":[{\"questionId\":1,\"studentAnswer\":\"A\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("submit: 他人 sessionId（service 抛 FORBIDDEN）→ 403")
    void submit_otherUsersSession_returns403() throws Exception {
        // 当前用户 2，studentId=2，但 sessionId=100 属于另一个学生 → service 抛 FORBIDDEN
        when(practiceService.submit(eq(100L), any()))
                .thenThrow(new BusinessException(ResultCode.FORBIDDEN, "会话归属校验失败：不能提交非本人的练习会话"));
        mvc.perform(post("/api/practice/100/submit")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"answers\":[{\"questionId\":1,\"studentAnswer\":\"A\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
    }

    // ── submit 入参校验（T4 遗留修复）──

    @Test
    @DisplayName("submit: 重复 questionId → 400（T4 遗留：防双写 answer_record）")
    void submit_duplicateQuestionIds_returns400() throws Exception {
        mvc.perform(post("/api/practice/100/submit")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"answers\":["
                                + "{\"questionId\":1,\"studentAnswer\":\"A\"},"
                                + "{\"questionId\":1,\"studentAnswer\":\"B\"}"
                                + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()));
    }

    @Test
    @DisplayName("submit: 不重复 questionId → 正常放行（不触发重复校验）")
    void submit_distinctQuestionIds_passes() throws Exception {
        when(practiceService.submit(any(), any())).thenReturn(new PracticeSubmitVO());
        mvc.perform(post("/api/practice/100/submit")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"answers\":["
                                + "{\"questionId\":1,\"studentAnswer\":\"A\"},"
                                + "{\"questionId\":2,\"studentAnswer\":\"B\"}"
                                + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ══════════════════════════════════════════════════════════════════════
    // GET /api/practice/history — assertSelf
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("history: 无 Authorization 头 → 401")
    void history_noToken_returns401() throws Exception {
        mvc.perform(get("/api/practice/history")
                        .param("studentId", "2")
                        .param("courseId", "5")
                        .param("nodeId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("history: token 归属他人（uid=9，studentId=2）→ 403")
    void history_otherUserToken_returns403() throws Exception {
        mvc.perform(get("/api/practice/history")
                        .header("Authorization", "Bearer " + tokenService.issue(9L, 2))
                        .param("studentId", "2")
                        .param("courseId", "5")
                        .param("nodeId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    @DisplayName("history: 合法 token + 本人 studentId → code=0，返回列表")
    void history_ownUser_passes() throws Exception {
        when(practiceService.getHistory(any(), any(), any())).thenReturn(List.of(new PracticeHistoryVO()));
        mvc.perform(get("/api/practice/history")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .param("studentId", "2")
                        .param("courseId", "5")
                        .param("nodeId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
    }
}
