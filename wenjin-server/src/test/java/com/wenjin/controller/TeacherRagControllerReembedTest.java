package com.wenjin.controller;

import com.wenjin.common.BusinessException;
import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.TeacherAuthInterceptor;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.rag.TeacherRagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TeacherRagControllerReembedTest {

    private TeacherRagService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(TeacherRagService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TeacherRagController(service))
                .addInterceptors(new TeacherAuthInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        CurrentUser.set(new CurrentPrincipal(2L, UserRole.TEACHER));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("POST /courses/{id}/reembed：200 + {model, sourceCount, jobIds}")
    void reembedOk() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "hash-ngram@1024");
        body.put("sourceCount", 2);
        body.put("jobIds", List.of(1L, 2L));
        when(service.reembedCourse(10L)).thenReturn(body);

        mvc.perform(post("/api/teacher/rag/courses/10/reembed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.model").value("hash-ngram@1024"))
                .andExpect(jsonPath("$.data.jobIds.length()").value(2));
    }

    @Test
    @DisplayName("开关关 → 404；无嵌入模型 → 503")
    void reembedErrors() throws Exception {
        when(service.reembedCourse(10L)).thenThrow(new BusinessException(ResultCode.NOT_FOUND, "x"));
        mvc.perform(post("/api/teacher/rag/courses/10/reembed")).andExpect(status().isNotFound());

        when(service.reembedCourse(11L)).thenThrow(new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "y"));
        mvc.perform(post("/api/teacher/rag/courses/11/reembed")).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("学生身份 → 403，不触达服务")
    void studentForbidden() throws Exception {
        CurrentUser.set(new CurrentPrincipal(3L, UserRole.STUDENT));
        mvc.perform(post("/api/teacher/rag/courses/10/reembed")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
