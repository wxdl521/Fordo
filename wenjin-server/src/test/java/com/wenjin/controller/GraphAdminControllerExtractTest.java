package com.wenjin.controller;

import com.wenjin.dto.AiJobVO;
import com.wenjin.service.AiJobPayloadSubmissionService;
import com.wenjin.service.GraphExtractReviewService;
import com.wenjin.service.GraphService;
import com.wenjin.support.AiJobKind;
import com.wenjin.support.AiJobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GraphAdminControllerExtractTest {

    @Test
    void extractEndpoint_returnsAcceptedJob() throws Exception {
        AiJobPayloadSubmissionService submissions = mock(AiJobPayloadSubmissionService.class);

        AiJobVO vo = new AiJobVO();
        vo.setId(42L);
        vo.setKind(AiJobKind.GRAPH_EXTRACT);
        vo.setStatus(AiJobStatus.PENDING);
        vo.setProgress(0);
        when(submissions.submitForCourseCode(
                eq("C1"), eq(AiJobKind.GRAPH_EXTRACT), isNull(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(vo);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new GraphAdminController(
                        mock(GraphService.class),
                        mock(GraphExtractReviewService.class),
                        submissions)).build();

        MockMultipartFile f = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});
        mvc.perform(multipart("/api/admin/graph/extract").file(f).param("courseCode", "C1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.kind").value(AiJobKind.GRAPH_EXTRACT));
    }

    @Test
    void importExcelEndpoint_submitsGraphImportKind() throws Exception {
        AiJobPayloadSubmissionService submissions = mock(AiJobPayloadSubmissionService.class);
        AiJobVO vo = new AiJobVO();
        vo.setId(43L);
        vo.setKind(AiJobKind.GRAPH_IMPORT_EXCEL);
        vo.setStatus(AiJobStatus.PENDING);
        when(submissions.submitForCourseCode(
                eq("C1"), eq(AiJobKind.GRAPH_IMPORT_EXCEL), isNull(),
                org.mockito.ArgumentMatchers.any())).thenReturn(vo);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new GraphAdminController(
                        mock(GraphService.class),
                        mock(GraphExtractReviewService.class),
                        submissions)).build();
        MockMultipartFile file = new MockMultipartFile(
                "file", "graph.xlsx", "application/octet-stream", new byte[]{1});

        mvc.perform(multipart("/api/admin/graph/import/excel")
                        .file(file)
                        .param("courseCode", "C1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(43))
                .andExpect(jsonPath("$.data.kind").value(AiJobKind.GRAPH_IMPORT_EXCEL));
        verify(submissions).submitForCourseCode(
                eq("C1"), eq(AiJobKind.GRAPH_IMPORT_EXCEL), isNull(),
                org.mockito.ArgumentMatchers.any());
    }
}
