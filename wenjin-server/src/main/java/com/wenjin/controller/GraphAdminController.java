package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.ExtractCommitResult;
import com.wenjin.dto.GraphExtractDraftResponse;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.dto.GraphImportResult;
import com.wenjin.entity.ExtractionReview;
import com.wenjin.service.GraphExtractReviewService;
import com.wenjin.service.GraphImportService;
import com.wenjin.service.GraphService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 图谱管理接口(教师/管理侧)。 */
@RestController
@RequestMapping("/api/admin/graph")
public class GraphAdminController {

    private final GraphService graphService;
    private final GraphImportService graphImportService;
    private final GraphExtractReviewService graphExtractReviewService;

    public GraphAdminController(GraphService graphService,
                                GraphImportService graphImportService,
                                GraphExtractReviewService graphExtractReviewService) {
        this.graphService = graphService;
        this.graphImportService = graphImportService;
        this.graphExtractReviewService = graphExtractReviewService;
    }

    @PostMapping("/import")
    public Result<GraphImportResult> importGraph(@RequestParam("courseCode") String courseCode,
                                                 @RequestBody GraphImportRequest request) {
        return Result.ok(graphService.importGraph(courseCode, request));
    }

    @PostMapping("/import/excel")
    public Result<GraphImportResult> importGraphFromExcel(
            @RequestParam("courseCode") String courseCode,
            @RequestParam("file") MultipartFile file) {
        return Result.ok(graphImportService.importFromExcel(courseCode, file));
    }

    /** 抽取课程标准 → 暂存草稿,返回 draftId + 草稿。 */
    @PostMapping("/extract")
    public Result<GraphExtractDraftResponse> extract(@RequestParam("courseCode") String courseCode,
                                                     @RequestParam("file") MultipartFile file) {
        return Result.ok(graphExtractReviewService.extractAndStash(courseCode, file));
    }

    /** 按 draftId 拉取暂存草稿(供审核页刷新)。 */
    @GetMapping("/extract/{draftId}")
    public Result<GraphImportRequest> getDraft(@PathVariable String draftId) {
        return Result.ok(graphExtractReviewService.getDraft(draftId));
    }

    /** 提交审核结果:全量替换导入 + 返回指标。 */
    @PostMapping("/extract/{draftId}/commit")
    public Result<ExtractCommitResult> commit(@PathVariable String draftId,
                                              @RequestBody GraphImportRequest finalGraph) {
        return Result.ok(graphExtractReviewService.commit(draftId, finalGraph));
    }

    /** 抽取审核指标历史(倒序)。 */
    @GetMapping("/extract/reviews")
    public Result<List<ExtractionReview>> reviews(@RequestParam("courseCode") String courseCode) {
        return Result.ok(graphExtractReviewService.history(courseCode));
    }
}
