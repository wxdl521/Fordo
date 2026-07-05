package com.wenjin.controller;

import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.common.ResultCode;
import com.wenjin.config.AuthContextInterceptor;
import com.wenjin.config.CurrentUser;
import com.wenjin.dto.ReviewTodayVO;
import com.wenjin.security.TokenService;
import com.wenjin.service.CourseService;
import com.wenjin.service.ReviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ReviewController 集成测试：assertSelf 鉴权接线。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewControllerTest {

    @Mock ReviewService reviewService;
    @Mock CourseService courseService;

    private final TokenService tokenService = new TokenService("test-secret", 3600);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        ReviewController controller = new ReviewController(reviewService, courseService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new AuthContextInterceptor(tokenService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearCurrentUser() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("today: 无 Authorization 头 → 401")
    void today_noToken_returns401() throws Exception {
        mvc.perform(get("/api/review/today").param("studentId", "2").param("courseId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("today: 他人 studentId → 403")
    void today_otherStudent_returns403() throws Exception {
        String token = tokenService.issue(2L, 2);
        mvc.perform(get("/api/review/today")
                        .header("Authorization", "Bearer " + token)
                        .param("studentId", "9")
                        .param("courseId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    @DisplayName("today: 本人 studentId → 200 code=0")
    void today_self_returnsOk() throws Exception {
        String token = tokenService.issue(2L, 2);
        ReviewTodayVO vo = new ReviewTodayVO();
        vo.setItems(List.of());
        vo.setTotalDecayingCount(0);
        when(reviewService.getToday(eq(2L), eq(1L))).thenReturn(vo);

        mvc.perform(get("/api/review/today")
                        .header("Authorization", "Bearer " + token)
                        .param("studentId", "2")
                        .param("courseId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.totalDecayingCount").value(0));
    }
}