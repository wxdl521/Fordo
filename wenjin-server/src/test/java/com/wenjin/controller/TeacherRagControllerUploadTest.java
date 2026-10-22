package com.wenjin.controller;

import com.wenjin.common.BusinessException;
import com.wenjin.common.GlobalExceptionHandler;
import com.wenjin.common.ResultCode;
import com.wenjin.config.CurrentUser;
import com.wenjin.config.TeacherAuthInterceptor;
import com.wenjin.dto.DocUploadVO;
import com.wenjin.security.CurrentPrincipal;
import com.wenjin.security.UserRole;
import com.wenjin.service.rag.TeacherRagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TeacherRagControllerUploadTest {

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

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "a.md", "text/markdown", "x".getBytes());
    }

    @Test
    @DisplayName("POST /sources/upload：202 + {sourceId, jobId, title, kind, status}，参数透传")
    void upload_accepted() throws Exception {
        DocUploadVO vo = new DocUploadVO();
        vo.setSourceId(5L);
        vo.setJobId(9L);
        vo.setTitle("讲义");
        vo.setKind(1);
        vo.setStatus(0);
        when(service.uploadSource(eq(10L), any(), eq("讲义"), eq(1))).thenReturn(vo);

        mvc.perform(multipart("/api/teacher/rag/sources/upload").file(file())
                        .param("courseId", "10").param("title", "讲义").param("kind", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sourceId").value(5))
                .andExpect(jsonPath("$.data.jobId").value(9))
                .andExpect(jsonPath("$.data.status").value(0))
                .andExpect(jsonPath("$.data.kind").value(1));
    }

    @Test
    @DisplayName("title/kind 可省略")
    void upload_optionalParams() throws Exception {
        when(service.uploadSource(eq(10L), any(), isNull(), isNull())).thenReturn(new DocUploadVO());
        mvc.perform(multipart("/api/teacher/rag/sources/upload").file(file()).param("courseId", "10"))
                .andExpect(status().isAccepted());
        verify(service).uploadSource(eq(10L), any(), isNull(), isNull());
    }

    @Test
    @DisplayName("重复上传：服务抛 409 → HTTP 409")
    void upload_conflict() throws Exception {
        when(service.uploadSource(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ResultCode.CONFLICT, "该文件已上传过"));
        mvc.perform(multipart("/api/teacher/rag/sources/upload").file(file()).param("courseId", "10"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @DisplayName("学生 403、未登录 401，服务不被调用")
    void upload_rbac() throws Exception {
        CurrentUser.set(new CurrentPrincipal(3L, UserRole.STUDENT));
        mvc.perform(multipart("/api/teacher/rag/sources/upload").file(file()).param("courseId", "10"))
                .andExpect(status().isForbidden());
        CurrentUser.clear();
        mvc.perform(multipart("/api/teacher/rag/sources/upload").file(file()).param("courseId", "10"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
