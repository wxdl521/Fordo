package com.wenjin.controller;

import com.wenjin.common.BusinessException;
import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AuthContextInterceptor;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.AdaptiveDiagnosticVO;
import com.wenjin.dto.AdaptiveDiagnosticVO.ProgressVO;
import com.wenjin.dto.AdaptiveDiagnosticVO.ProbeVO;
import com.wenjin.dto.AdaptiveDiagnosticVO.SummaryVO;
import com.wenjin.dto.EvidenceVO;
import com.wenjin.dto.PaperQuestionVO;
import com.wenjin.security.TokenService;
import com.wenjin.service.AdaptiveDiagnosticService;
import com.wenjin.service.CourseService;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdaptiveDiagnosticController 全链路集成测试（standalone MockMvc）。
 *
 * <p>串联真实 {@link AuthContextInterceptor}、真实 {@code AccessGuard}、
 * 真实 Controller、真实 {@link GlobalExceptionHandler}，验证：
 * <ul>
 *   <li>三端点均要求有效 Bearer 令牌（无 token → 401，伪造 token → 401）</li>
 *   <li>三端点均要求 studentId 归属当前用户（他人 studentId → 403）</li>
 *   <li>start 端点：assertAccessibleByStudent 未选课/未发布 → 403</li>
 *   <li>answer/abandon：他人 sessionId（service 抛 FORBIDDEN）→ 403（session 属主反查）</li>
 *   <li>不存在的 sessionId → 404（service 抛 NOT_FOUND）</li>
 *   <li>完整会话端到端：start → answer → done（断言响应形状+脱敏+progress.budgetLeft）</li>
 *   <li>abandon 端到端：start → abandon → answer → 400</li>
 *   <li>固定卷零回归：DiagnosticController 未被触碰（通过独立 Controller 验证）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdaptiveDiagnosticControllerTest {

    @Mock AdaptiveDiagnosticService adaptiveDiagnosticService;
    @Mock CourseService courseService;

    private final TokenService tokenService = new TokenService("test-secret", 3600);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        AdaptiveDiagnosticController controller =
                new AdaptiveDiagnosticController(adaptiveDiagnosticService, courseService);
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
    // 测试 1：401 — 无 token / 伪 token 调三端点
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("start: 无 Authorization 头 → 401")
    void start_noToken_returns401() throws Exception {
        mvc.perform(post("/api/diagnostic/adaptive/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("start: 伪造 token → 401")
    void start_forgedToken_returns401() throws Exception {
        mvc.perform(post("/api/diagnostic/adaptive/start")
                        .header("Authorization", "Bearer forged.token.here")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("answer: 无 Authorization 头 → 401")
    void answer_noToken_returns401() throws Exception {
        mvc.perform(post("/api/diagnostic/adaptive/100/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"answers\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("answer: 伪造 token → 401")
    void answer_forgedToken_returns401() throws Exception {
        mvc.perform(post("/api/diagnostic/adaptive/100/answer")
                        .header("Authorization", "Bearer forged.token.here")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"answers\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("abandon: 无 Authorization 头 → 401")
    void abandon_noToken_returns401() throws Exception {
        mvc.perform(post("/api/diagnostic/adaptive/100/abandon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("abandon: 伪造 token → 401")
    void abandon_forgedToken_returns401() throws Exception {
        mvc.perform(post("/api/diagnostic/adaptive/100/abandon")
                        .header("Authorization", "Bearer forged.token.here")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 测试 2：403 assertSelf — A 的 token 带 B 的 studentId
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("start: token 归属他人（uid=9，studentId=2）→ 403")
    void start_otherUserToken_returns403() throws Exception {
        mvc.perform(post("/api/diagnostic/adaptive/start")
                        .header("Authorization", "Bearer " + tokenService.issue(9L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    @DisplayName("answer: token 归属他人（uid=9，studentId=2）→ 403")
    void answer_otherUserToken_returns403() throws Exception {
        mvc.perform(post("/api/diagnostic/adaptive/100/answer")
                        .header("Authorization", "Bearer " + tokenService.issue(9L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"answers\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    @DisplayName("abandon: token 归属他人（uid=9，studentId=2）→ 403")
    void abandon_otherUserToken_returns403() throws Exception {
        mvc.perform(post("/api/diagnostic/adaptive/100/abandon")
                        .header("Authorization", "Bearer " + tokenService.issue(9L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 测试 3：403 属主 — A 建会话，B 用自己 token answer/abandon A 的 session
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("answer: 他人 sessionId（service 抛 FORBIDDEN）→ 403")
    void answer_otherUsersSession_returns403() throws Exception {
        // 当前用户 2，studentId=2，但 sessionId=100 属于另一个学生 → service 抛 FORBIDDEN
        when(adaptiveDiagnosticService.answer(eq(100L), any()))
                .thenThrow(new BusinessException(ResultCode.FORBIDDEN, "会话归属校验失败"));
        mvc.perform(post("/api/diagnostic/adaptive/100/answer")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"answers\":[{\"questionId\":1,\"studentAnswer\":\"A\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    @DisplayName("abandon: 他人 sessionId（service 抛 FORBIDDEN）→ 403")
    void abandon_otherUsersSession_returns403() throws Exception {
        doThrow(new BusinessException(ResultCode.FORBIDDEN, "会话归属校验失败"))
                .when(adaptiveDiagnosticService).abandon(eq(100L), eq(2L));
        mvc.perform(post("/api/diagnostic/adaptive/100/abandon")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 测试 4：403 课程边界 — 未选课/未发布课程 start → 403
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("start: 未选课/未发布（assertAccessibleByStudent 抛 FORBIDDEN）→ 403")
    void start_courseNotAccessible_returns403() throws Exception {
        // assertAccessibleByStudent 抛 FORBIDDEN（课程未发布 / 未选课）
        doThrow(new BusinessException(ResultCode.FORBIDDEN, "课程未发布或未选课"))
                .when(courseService).assertAccessibleByStudent(eq(2L), eq(5L));
        mvc.perform(post("/api/diagnostic/adaptive/start")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
        // service 层不应被调用（assertAccessibleByStudent 短路）
        verify(adaptiveDiagnosticService, never()).start(anyLong(), anyLong());
    }

    // ══════════════════════════════════════════════════════════════════════
    // 测试 5：404 — 不存在的 sessionId
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("answer: 不存在的 sessionId（service 抛 NOT_FOUND）→ 404")
    void answer_sessionNotFound_returns404() throws Exception {
        when(adaptiveDiagnosticService.answer(eq(999L), any()))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "会话不存在"));
        mvc.perform(post("/api/diagnostic/adaptive/999/answer")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"answers\":[{\"questionId\":1,\"studentAnswer\":\"A\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("abandon: 不存在的 sessionId（service 抛 NOT_FOUND）→ 404")
    void abandon_sessionNotFound_returns404() throws Exception {
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "会话不存在"))
                .when(adaptiveDiagnosticService).abandon(eq(999L), eq(2L));
        mvc.perform(post("/api/diagnostic/adaptive/999/abandon")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.getCode()));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 测试 6：完整会话端到端
    //   start（断言 probe 有题目、题目无 answer/isCorrect/pointNodeCode、无 nodeName、
    //          progress.budgetLeft=15）
    //   → answer（未终止：下一探针）
    //   → answer（终局：done=true、summary 四类计数+nodes 明细、resultUrl）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("完整会话端到端：start→answer(未终止)→answer(终局)，断言形状+脱敏+progress")
    void fullSession_startAnswerDone() throws Exception {
        // ── start 响应：首探针 + 进度 ──
        AdaptiveDiagnosticVO startVo = buildStartVo();
        when(adaptiveDiagnosticService.start(eq(2L), eq(5L))).thenReturn(startVo);

        mvc.perform(post("/api/diagnostic/adaptive/start")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sessionId").value(100))
                .andExpect(jsonPath("$.data.done").value(false))
                .andExpect(jsonPath("$.data.probe.nodeId").value(10))
                // 脱敏：无 nodeName（探针只含 nodeId，不含节点名）
                .andExpect(jsonPath("$.data.probe.nodeName").doesNotExist())
                // 题目有内容，且无 answer/isCorrect/pointNodeCode
                .andExpect(jsonPath("$.data.probe.questions[0].questionId").value(101))
                .andExpect(jsonPath("$.data.probe.questions[0].answer").doesNotExist())
                .andExpect(jsonPath("$.data.probe.questions[0].isCorrect").doesNotExist())
                .andExpect(jsonPath("$.data.probe.questions[0].pointNodeCode").doesNotExist())
                .andExpect(jsonPath("$.data.progress.budgetLeft").value(15))
                .andExpect(jsonPath("$.data.progress.tested").value(0))
                .andExpect(jsonPath("$.data.progress.inferred").value(0))
                .andExpect(jsonPath("$.data.summary").doesNotExist());

        // ── answer 未终止：下一探针 ──
        AdaptiveDiagnosticVO midVo = buildMidVo();
        when(adaptiveDiagnosticService.answer(eq(100L), any())).thenReturn(midVo);

        mvc.perform(post("/api/diagnostic/adaptive/100/answer")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"answers\":[{\"questionId\":101,\"studentAnswer\":\"A\"},"
                                + "{\"questionId\":102,\"studentAnswer\":\"A\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.done").value(false))
                .andExpect(jsonPath("$.data.probe.nodeId").value(20))
                .andExpect(jsonPath("$.data.progress.tested").value(1))
                .andExpect(jsonPath("$.data.progress.budgetLeft").value(13));

        // ── answer 终局：done=true + summary ──
        AdaptiveDiagnosticVO doneVo = buildDoneVo();
        when(adaptiveDiagnosticService.answer(eq(100L), any())).thenReturn(doneVo);

        mvc.perform(post("/api/diagnostic/adaptive/100/answer")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"answers\":[{\"questionId\":201,\"studentAnswer\":\"A\"},"
                                + "{\"questionId\":202,\"studentAnswer\":\"A\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.done").value(true))
                .andExpect(jsonPath("$.data.probe").doesNotExist())
                // summary 四类计数
                .andExpect(jsonPath("$.data.summary.tested").value(2))
                .andExpect(jsonPath("$.data.summary.inferredPass").value(1))
                .andExpect(jsonPath("$.data.summary.inferredFail").value(0))
                .andExpect(jsonPath("$.data.summary.unknown").value(0))
                // nodes 明细（nodeId/nodeCode/name/state 均存在）
                .andExpect(jsonPath("$.data.summary.nodes[0].nodeId").value(10))
                .andExpect(jsonPath("$.data.summary.nodes[0].nodeCode").value("KT10"))
                .andExpect(jsonPath("$.data.summary.nodes[0].name").value("节点10"))
                .andExpect(jsonPath("$.data.summary.nodes[0].state").value("TESTED_PASS"))
                // resultUrl 指向结果页（T8 修正为 /result）
                .andExpect(jsonPath("$.data.resultUrl").value("/result?courseId=5"));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 测试 7：abandon 端到端 — start → abandon → answer → 400
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("abandon 端到端：start → abandon(成功) → answer → 400")
    void abandonSession_thenAnswer_returns400() throws Exception {
        // start 成功
        when(adaptiveDiagnosticService.start(eq(2L), eq(5L))).thenReturn(buildStartVo());
        mvc.perform(post("/api/diagnostic/adaptive/start")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5}"))
                .andExpect(jsonPath("$.code").value(0));

        // abandon 成功（service 无异常）
        mvc.perform(post("/api/diagnostic/adaptive/100/abandon")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // answer 已放弃会话 → 400（service 抛 BAD_REQUEST）
        when(adaptiveDiagnosticService.answer(eq(100L), any()))
                .thenThrow(new BusinessException(ResultCode.BAD_REQUEST, "会话已放弃，不能继续作答"));
        mvc.perform(post("/api/diagnostic/adaptive/100/answer")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"answers\":[{\"questionId\":101,\"studentAnswer\":\"A\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.BAD_REQUEST.getCode()));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 测试 8：固定卷零回归
    //   start 合法请求放行（code=0），verify assertSelf + assertAccessibleByStudent 均被调
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("start: 合法 token + 本人 studentId → code=0，assertSelf + assertAccessibleByStudent 均被调")
    void start_ownUser_passes() throws Exception {
        when(adaptiveDiagnosticService.start(eq(2L), eq(5L))).thenReturn(buildStartVo());
        mvc.perform(post("/api/diagnostic/adaptive/start")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"courseId\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        // assertAccessibleByStudent 确实被调（不短路）
        verify(courseService).assertAccessibleByStudent(eq(2L), eq(5L));
    }

    @Test
    @DisplayName("answer: 合法 token + 本人 studentId → code=0")
    void answer_ownUser_passes() throws Exception {
        when(adaptiveDiagnosticService.answer(eq(100L), any())).thenReturn(buildMidVo());
        mvc.perform(post("/api/diagnostic/adaptive/100/answer")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2,\"answers\":[{\"questionId\":101,\"studentAnswer\":\"A\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("abandon: 合法 token + 本人 studentId → code=0")
    void abandon_ownUser_passes() throws Exception {
        mvc.perform(post("/api/diagnostic/adaptive/100/abandon")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(adaptiveDiagnosticService).abandon(eq(100L), eq(2L));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 鉴权接线验证（不依赖 MockMvc，直接调 Controller）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("start 接线：assertSelf + assertAccessibleByStudent + 委托 service")
    void start_wiring_assertSelfThenGuardThenService() {
        CurrentUser.set(2L);
        AdaptiveDiagnosticController c =
                new AdaptiveDiagnosticController(adaptiveDiagnosticService, courseService);
        AdaptiveDiagnosticController.AdaptiveStartRequest req =
                new AdaptiveDiagnosticController.AdaptiveStartRequest();
        req.setStudentId(2L);
        req.setCourseId(5L);
        when(adaptiveDiagnosticService.start(eq(2L), eq(5L))).thenReturn(buildStartVo());

        c.start(req);

        verify(courseService).assertAccessibleByStudent(eq(2L), eq(5L));
        verify(adaptiveDiagnosticService).start(eq(2L), eq(5L));
    }

    @Test
    @DisplayName("start 接线：assertAccessibleByStudent 抛异常 → service 不被调")
    void start_wiring_guardRejects_serviceNotCalled() {
        CurrentUser.set(2L);
        doThrow(new BusinessException(ResultCode.FORBIDDEN, "课程未发布"))
                .when(courseService).assertAccessibleByStudent(eq(2L), eq(5L));
        AdaptiveDiagnosticController c =
                new AdaptiveDiagnosticController(adaptiveDiagnosticService, courseService);
        AdaptiveDiagnosticController.AdaptiveStartRequest req =
                new AdaptiveDiagnosticController.AdaptiveStartRequest();
        req.setStudentId(2L);
        req.setCourseId(5L);

        assertThatThrownBy(() -> c.start(req)).isInstanceOf(BusinessException.class);
        verify(adaptiveDiagnosticService, never()).start(anyLong(), anyLong());
    }

    // ══════════════════════════════════════════════════════════════════════
    // 测试 9：evidence 端点鉴权（T8）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("evidence: 无 Authorization 头 → 401")
    void evidence_noToken_returns401() throws Exception {
        mvc.perform(get("/api/diagnostic/adaptive/evidence")
                        .param("studentId", "2").param("courseId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("evidence: 伪造 token → 401")
    void evidence_forgedToken_returns401() throws Exception {
        mvc.perform(get("/api/diagnostic/adaptive/evidence")
                        .header("Authorization", "Bearer forged.token.here")
                        .param("studentId", "2").param("courseId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("evidence: token 归属他人（uid=9，studentId=2）→ 403（assertSelf 越权）")
    void evidence_otherUserToken_returns403() throws Exception {
        mvc.perform(get("/api/diagnostic/adaptive/evidence")
                        .header("Authorization", "Bearer " + tokenService.issue(9L, 2))
                        .param("studentId", "2").param("courseId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    @DisplayName("evidence: assertAccessibleByStudent 抛 FORBIDDEN → 403（课程边界短路）")
    void evidence_courseNotAccessible_returns403() throws Exception {
        doThrow(new BusinessException(ResultCode.FORBIDDEN, "课程未发布或未选课"))
                .when(courseService).assertAccessibleByStudent(eq(2L), eq(5L));
        mvc.perform(get("/api/diagnostic/adaptive/evidence")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .param("studentId", "2").param("courseId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
        verify(adaptiveDiagnosticService, never()).latestEvidence(anyLong(), anyLong());
    }

    @Test
    @DisplayName("evidence: 合法请求，service 返回非 null → code=0 + data 形状正确")
    void evidence_validRequest_returnsEvidence() throws Exception {
        EvidenceVO ev = buildEvidenceVO();
        when(adaptiveDiagnosticService.latestEvidence(eq(2L), eq(5L))).thenReturn(ev);
        mvc.perform(get("/api/diagnostic/adaptive/evidence")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .param("studentId", "2").param("courseId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sessionId").value(100))
                .andExpect(jsonPath("$.data.tested").value(1))
                .andExpect(jsonPath("$.data.inferredPass").value(1))
                .andExpect(jsonPath("$.data.inferredFail").value(0))
                .andExpect(jsonPath("$.data.unknown").value(1))
                .andExpect(jsonPath("$.data.inferredNodes[0].nodeId").value(10))
                .andExpect(jsonPath("$.data.inferredNodes[0].name").value("节点A"))
                .andExpect(jsonPath("$.data.inferredNodes[0].state").value("INFERRED_PASS"))
                .andExpect(jsonPath("$.data.inferredNodes[0].fromNodeId").value(20))
                .andExpect(jsonPath("$.data.inferredNodes[0].fromName").value("节点B"))
                .andExpect(jsonPath("$.data.inferredNodes[0].fromPass").value(true));
    }

    @Test
    @DisplayName("evidence: 合法请求，service 返回 null（无已完成会话）→ code=0 + data=null")
    void evidence_noCompletedSession_returnsNull() throws Exception {
        when(adaptiveDiagnosticService.latestEvidence(eq(2L), eq(5L))).thenReturn(null);
        mvc.perform(get("/api/diagnostic/adaptive/evidence")
                        .header("Authorization", "Bearer " + tokenService.issue(2L, 2))
                        .param("studentId", "2").param("courseId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ── fixture helpers ──────────────────────────────────────────────────

    /** start 响应：首探针 nodeId=10，两题（无 answer/isCorrect/pointNodeCode），budgetLeft=15。 */
    private AdaptiveDiagnosticVO buildStartVo() {
        AdaptiveDiagnosticVO vo = new AdaptiveDiagnosticVO();
        vo.setSessionId(100L);
        vo.setDone(false);

        ProbeVO probe = new ProbeVO();
        probe.setNodeId(10L);
        // 脱敏题目：PaperQuestionVO 只包含 questionId/stem/type/options，无 answer/isCorrect/pointNodeCode
        PaperQuestionVO q1 = new PaperQuestionVO();
        q1.setQuestionId(101L);
        q1.setStem("题干101");
        probe.setQuestions(List.of(q1));
        vo.setProbe(probe);

        ProgressVO prog = new ProgressVO();
        prog.setTested(0);
        prog.setInferred(0);
        prog.setUnknown(2);
        prog.setBudgetLeft(15);
        vo.setProgress(prog);
        return vo;
    }

    /** answer 未终止响应：下一探针 nodeId=20，budgetLeft=13。 */
    private AdaptiveDiagnosticVO buildMidVo() {
        AdaptiveDiagnosticVO vo = new AdaptiveDiagnosticVO();
        vo.setSessionId(100L);
        vo.setDone(false);

        ProbeVO probe = new ProbeVO();
        probe.setNodeId(20L);
        PaperQuestionVO q1 = new PaperQuestionVO();
        q1.setQuestionId(201L);
        probe.setQuestions(List.of(q1));
        vo.setProbe(probe);

        ProgressVO prog = new ProgressVO();
        prog.setTested(1);
        prog.setInferred(0);
        prog.setUnknown(1);
        prog.setBudgetLeft(13);
        vo.setProgress(prog);
        return vo;
    }

    /** answer 终局响应：done=true，summary 四类计数，nodes 明细，resultUrl。 */
    private AdaptiveDiagnosticVO buildDoneVo() {
        AdaptiveDiagnosticVO vo = new AdaptiveDiagnosticVO();
        vo.setSessionId(100L);
        vo.setDone(true);

        ProgressVO prog = new ProgressVO();
        prog.setTested(2);
        prog.setInferred(1);
        prog.setUnknown(0);
        prog.setBudgetLeft(11);
        vo.setProgress(prog);

        SummaryVO summary = new SummaryVO();
        summary.setTested(2);
        summary.setInferredPass(1);
        summary.setInferredFail(0);
        summary.setUnknown(0);
        AdaptiveDiagnosticVO.NodeDetailVO nd = new AdaptiveDiagnosticVO.NodeDetailVO();
        nd.setNodeId(10L);
        nd.setNodeCode("KT10");
        nd.setName("节点10");
        nd.setState("TESTED_PASS");
        summary.setNodes(List.of(nd));
        vo.setSummary(summary);

        vo.setResultUrl("/result?courseId=5"); // T8 修正
        return vo;
    }

    /** evidence 响应：1 实测，1 推断通过，0 推断失败，1 未知；1 条 inferredNodes。 */
    private EvidenceVO buildEvidenceVO() {
        EvidenceVO ev = new EvidenceVO();
        ev.setSessionId(100L);
        ev.setFinishedAt(LocalDateTime.of(2026, 7, 5, 10, 0, 0));
        ev.setTested(1);
        ev.setInferredPass(1);
        ev.setInferredFail(0);
        ev.setUnknown(1);

        EvidenceVO.InferredNodeVO n = new EvidenceVO.InferredNodeVO();
        n.setNodeId(10L);
        n.setName("节点A");
        n.setState("INFERRED_PASS");
        n.setFromNodeId(20L);
        n.setFromName("节点B");
        n.setFromPass(true);
        ev.setInferredNodes(List.of(n));
        return ev;
    }
}
