package com.wenjin.service;

import com.wenjin.dto.ExtractCommitResult;
import com.wenjin.dto.GraphExtractDraftResponse;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.dto.GraphImportResult;
import com.wenjin.entity.ExtractionReview;
import com.wenjin.mapper.ExtractionReviewMapper;
import com.wenjin.support.GraphDraftStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphExtractReviewServiceTest {

    private final GraphExtractionService extraction = mock(GraphExtractionService.class);
    private final GraphDraftStore store = new GraphDraftStore(); // 真实内存store,验证存取
    private final ExtractionMetricsCalculator calc = new ExtractionMetricsCalculator();
    private final GraphService graphService = mock(GraphService.class);
    private final ExtractionReviewMapper mapper = mock(ExtractionReviewMapper.class);

    private final GraphExtractReviewService svc =
            new GraphExtractReviewService(extraction, store, calc, graphService, mapper);

    @Test
    void extractAndStash_storesAndReturnsDraftId() {
        GraphImportRequest draft = new GraphImportRequest();
        when(extraction.extract(eq("C1"), any())).thenReturn(draft);

        GraphExtractDraftResponse resp = svc.extractAndStash("C1",
                new MockMultipartFile("file", "a.png", "image/png", new byte[]{1}));

        assertEquals(draft, svc.getDraft(resp.getDraftId())); // 暂存可取回
    }

    @Test
    void commit_calcMetrics_imports_persistsReview() {
        // 暂存一个 2 节点草稿
        GraphImportRequest ai = new GraphImportRequest();
        ai.setNodes(new java.util.ArrayList<>());
        GraphImportRequest.NodeItem a = new GraphImportRequest.NodeItem();
        a.setId("A"); a.setName("甲");
        GraphImportRequest.NodeItem b = new GraphImportRequest.NodeItem();
        b.setId("B"); b.setName("乙");
        ai.getNodes().add(a);
        ai.getNodes().add(b);
        ai.setEdges(new java.util.ArrayList<>());
        String id = store.save("C1", ai);

        // 最终:保留 A,删 B
        GraphImportRequest fin = new GraphImportRequest();
        fin.setNodes(new java.util.ArrayList<>(java.util.List.of(a)));
        fin.setEdges(new java.util.ArrayList<>());

        when(graphService.importGraph(eq("C1"), any()))
                .thenReturn(new GraphImportResult(7L, "C1", "软件工程", 1, 0));

        ExtractCommitResult result = svc.commit(id, fin);

        assertEquals(7L, result.getImportResult().getCourseId());
        assertEquals(1, result.getMetrics().getNode().getKeptCount());
        assertEquals(1, result.getMetrics().getNode().getDeletedCount());

        ArgumentCaptor<ExtractionReview> cap = ArgumentCaptor.forClass(ExtractionReview.class);
        verify(mapper).insert(cap.capture());
        assertEquals("C1", cap.getValue().getCourseCode());
        assertEquals(7L, cap.getValue().getCourseId());
        assertEquals(1, cap.getValue().getNodeKeptCount());
    }
}
