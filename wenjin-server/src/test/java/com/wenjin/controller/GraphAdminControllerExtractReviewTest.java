package com.wenjin.controller;

import com.wenjin.dto.ExtractCommitResult;
import com.wenjin.dto.ExtractionMetrics;
import com.wenjin.dto.GraphImportResult;
import com.wenjin.entity.ExtractionReview;
import com.wenjin.service.GraphExtractReviewService;
import com.wenjin.service.GraphImportService;
import com.wenjin.service.GraphService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GraphAdminControllerExtractReviewTest {

    private final GraphExtractReviewService svc = mock(GraphExtractReviewService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new GraphAdminController(mock(GraphService.class), mock(GraphImportService.class), svc)).build();

    @Test
    void commit_returnsMetrics() throws Exception {
        ExtractionMetrics m = new ExtractionMetrics();
        m.getNode().setKeptCount(5);
        when(svc.commit(eq("draft-1"), any()))
                .thenReturn(new ExtractCommitResult(new GraphImportResult(1L, "C1", "软工", 5, 3), m));

        mvc.perform(post("/api/admin/graph/extract/draft-1/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodes\":[],\"edges\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.metrics.node.keptCount").value(5))
                .andExpect(jsonPath("$.data.importResult.nodeCount").value(5));
    }

    @Test
    void reviews_returnsHistory() throws Exception {
        ExtractionReview r = new ExtractionReview();
        r.setCourseCode("C1");
        r.setNodeRecall(new java.math.BigDecimal("0.8000"));
        when(svc.history("C1")).thenReturn(List.of(r));

        mvc.perform(get("/api/admin/graph/extract/reviews").param("courseCode", "C1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].courseCode").value("C1"));
    }
}
