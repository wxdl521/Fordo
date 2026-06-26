package com.wenjin.controller;

import com.wenjin.dto.GraphExtractDraftResponse;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.service.GraphExtractReviewService;
import com.wenjin.service.GraphImportService;
import com.wenjin.service.GraphService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GraphAdminControllerExtractTest {

    @Test
    void extractEndpoint_returnsDraftId() throws Exception {
        GraphExtractReviewService svc = mock(GraphExtractReviewService.class);
        when(svc.extractAndStash(eq("C1"), any()))
                .thenReturn(new GraphExtractDraftResponse("draft-1", new GraphImportRequest()));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new GraphAdminController(mock(GraphService.class), mock(GraphImportService.class), svc)).build();

        MockMultipartFile f = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});
        mvc.perform(multipart("/api/admin/graph/extract").file(f).param("courseCode", "C1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.draftId").value("draft-1"));
    }
}
