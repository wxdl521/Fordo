package com.wenjin.controller;

import com.wenjin.common.BusinessException;
import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.TestPrincipalSupport;
import com.wenjin.dto.CompanionOpenerVO;
import com.wenjin.security.TokenService;
import com.wenjin.service.CourseService;
import com.wenjin.service.proactive.CompanionOpenerService;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanionOpenerControllerTest {

    @Mock CompanionOpenerService openerService;
    @Mock CourseService courseService;

    private final TokenService tokenService = new TokenService("test-secret", 3600);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new CompanionOpenerController(openerService, courseService))
                .addInterceptors(TestPrincipalSupport.authInterceptor(tokenService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        CurrentUser.clear();
    }

    private String student() {
        return "Bearer " + tokenService.issue(2L, 2);
    }

    @Test
    @DisplayName("三个端点无令牌 -> 401")
    void noToken_401() throws Exception {
        mvc.perform(get("/api/companion/opener").param("courseId", "1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/companion/hints/count").param("courseId", "1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/companion/hints/dismiss").contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\":1,\"hintKey\":\"DECAY:x\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("opener：200，studentId 取自令牌；契约字段齐全，null 的 cta 字段省略")
    void opener_ok() throws Exception {
        CompanionOpenerVO vo = new CompanionOpenerVO();
        vo.setGreeting("上午好");
        CompanionOpenerVO.Cta cta = new CompanionOpenerVO.Cta();
        cta.setPrompt("p");
        CompanionOpenerVO.Action a = new CompanionOpenerVO.Action();
        a.setKind("WEAK_NODE");
        a.setTitle("t");
        a.setReason("r");
        a.setNodeCode("N1");
        a.setNodeName("变量");
        a.setCta(cta);
        vo.setActions(List.of(a));
        CompanionOpenerVO.Hint h = new CompanionOpenerVO.Hint();
        h.setHintKey("DECAY:2026-10-21");
        h.setHintType("DECAY");
        h.setTitle("t");
        h.setReason("r");
        vo.setHints(List.of(h));
        when(openerService.build(2L, 1L)).thenReturn(vo);

        mvc.perform(get("/api/companion/opener").param("courseId", "1").header("Authorization", student()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.greeting").value("上午好"))
                .andExpect(jsonPath("$.data.actions[0].kind").value("WEAK_NODE"))
                .andExpect(jsonPath("$.data.actions[0].nodeCode").value("N1"))
                .andExpect(jsonPath("$.data.actions[0].cta.prompt").value("p"))
                .andExpect(jsonPath("$.data.actions[0].cta.reviewPracticeSessionId").doesNotExist())
                .andExpect(jsonPath("$.data.hints[0].hintKey").value("DECAY:2026-10-21"))
                .andExpect(jsonPath("$.data.hints[0].cta").doesNotExist());
        verify(courseService).assertAccessibleByStudent(2L, 1L);
    }

    @Test
    @DisplayName("opener：开关关闭（service 404）-> 404")
    void opener_featureOff_404() throws Exception {
        when(openerService.build(anyLong(), anyLong()))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "资源不存在"));
        mvc.perform(get("/api/companion/opener").param("courseId", "1").header("Authorization", student()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("opener：未选课/下架课程 -> 403 且不查 opener；课程不存在 -> 404")
    void opener_courseNotAccessible() throws Exception {
        doThrow(new BusinessException(ResultCode.FORBIDDEN, "课程未发布"))
                .when(courseService).assertAccessibleByStudent(2L, 7L);
        doThrow(new BusinessException(ResultCode.NOT_FOUND, "课程不存在"))
                .when(courseService).assertAccessibleByStudent(2L, 8L);
        mvc.perform(get("/api/companion/opener").param("courseId", "7").header("Authorization", student()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/companion/opener").param("courseId", "8").header("Authorization", student()))
                .andExpect(status().isNotFound());
        verify(openerService, never()).build(anyLong(), anyLong());
    }

    @Test
    @DisplayName("hints/count：返回 {count}")
    void count_ok() throws Exception {
        when(openerService.hintCount(2L, 1L)).thenReturn(3);
        mvc.perform(get("/api/companion/hints/count").param("courseId", "1").header("Authorization", student()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.count").value(3));
    }

    @Test
    @DisplayName("hints/dismiss：以令牌 studentId 关闭；hintKey 空 -> 400")
    void dismiss_ok_andValidation() throws Exception {
        mvc.perform(post("/api/companion/hints/dismiss").header("Authorization", student())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":1,\"hintKey\":\"DECAY:2026-10-21\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(openerService).dismiss(eq(2L), eq(1L), eq("DECAY:2026-10-21"));

        mvc.perform(post("/api/companion/hints/dismiss").header("Authorization", student())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":1,\"hintKey\":\"\"}"))
                .andExpect(status().isBadRequest());
        verify(openerService, never()).dismiss(anyLong(), anyLong(), eq(""));
    }

    @Test
    @DisplayName("hints/dismiss：课程不可访问 -> 403 不落库")
    void dismiss_courseNotAccessible() throws Exception {
        doThrow(new BusinessException(ResultCode.FORBIDDEN, "未选课"))
                .when(courseService).assertAccessibleByStudent(2L, 7L);
        mvc.perform(post("/api/companion/hints/dismiss").header("Authorization", student())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":7,\"hintKey\":\"DECAY:x\"}"))
                .andExpect(status().isForbidden());
        verify(openerService, never()).dismiss(anyLong(), anyLong(), any());
    }
}
