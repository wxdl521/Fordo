package com.wenjin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.dto.AiJobVO;
import com.wenjin.service.AiJobPayloadSubmissionService;
import com.wenjin.service.AiJobService;
import com.wenjin.service.QuestionBankImportService;
import com.wenjin.support.AiJobKind;
import com.wenjin.support.AiJobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QuestionAdminControllerImportExcelTest {

    @Test
    void importExcelEndpointSubmitsDurableQuestionImportPayload() throws Exception {
        AiJobPayloadSubmissionService submissions = mock(AiJobPayloadSubmissionService.class);
        AiJobVO job = new AiJobVO();
        job.setId(88L);
        job.setKind(AiJobKind.QUESTION_IMPORT_EXCEL);
        job.setStatus(AiJobStatus.PENDING);
        when(submissions.submitForCourse(
                eq(7L), eq(AiJobKind.QUESTION_IMPORT_EXCEL), eq("question-import-1"), any()))
                .thenReturn(job);
        QuestionAdminController controller = new QuestionAdminController(
                mock(QuestionBankImportService.class),
                new ObjectMapper(),
                mock(AiJobService.class),
                submissions);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        MockMultipartFile file = new MockMultipartFile(
                "file", "questions.xlsx", "application/octet-stream", new byte[]{1, 2, 3});

        mvc.perform(multipart("/api/admin/question/import/excel")
                        .file(file)
                        .param("courseId", "7")
                        .header("Idempotency-Key", "question-import-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(88))
                .andExpect(jsonPath("$.data.kind").value(AiJobKind.QUESTION_IMPORT_EXCEL));

        verify(submissions).submitForCourse(
                eq(7L), eq(AiJobKind.QUESTION_IMPORT_EXCEL), eq("question-import-1"), any());
    }
}
