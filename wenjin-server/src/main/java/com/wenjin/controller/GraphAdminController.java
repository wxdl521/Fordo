package com.wenjin.controller;

import com.wenjin.audit.Audited;
import com.wenjin.common.Result;
import com.wenjin.dto.AiJobVO;
import com.wenjin.dto.ExtractCommitResult;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.dto.GraphImportResult;
import com.wenjin.entity.ExtractionReview;
import com.wenjin.service.AiJobPayloadSubmissionService;
import com.wenjin.service.GraphExtractReviewService;
import com.wenjin.service.GraphService;
import com.wenjin.support.AiJobKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 图谱管理接口(教师/管理侧)。长写 import/excel 与 extract 派发为 AI 作业（HTTP 202）。 */
@RestController
@RequestMapping("/api/admin/graph")
@Validated
public class GraphAdminController {

    private final GraphService graphService;
    private final GraphExtractReviewService graphExtractReviewService;
    private final AiJobPayloadSubmissionService payloadSubmissionService;

    public GraphAdminController(GraphService graphService,
                                GraphExtractReviewService graphExtractReviewService,
                                AiJobPayloadSubmissionService payloadSubmissionService) {
        this.graphService = graphService;
        this.graphExtractReviewService = graphExtractReviewService;
        this.payloadSubmissionService = payloadSubmissionService;
    }

    @Audited(action = "GRAPH_IMPORT", target = "GRAPH")
    @PostMapping("/import")
    public Result<GraphImportResult> importGraph(@RequestParam("courseCode") @NotBlank String courseCode,
                                                 @Valid @RequestBody GraphImportRequest request) {
        return Result.ok(graphService.importGraph(courseCode, request));
    }

    /** Excel 图谱导入（含 AI 清洗）→ 异步作业 202 */
    @Audited(action = "GRAPH_IMPORT", target = "GRAPH")
    @PostMapping("/import/excel")
    public ResponseEntity<Result<AiJobVO>> importGraphFromExcel(
            @RequestParam("courseCode") @NotBlank String courseCode,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        AiJobVO job = payloadSubmissionService.submitForCourseCode(
                courseCode, AiJobKind.GRAPH_IMPORT_EXCEL, idempotencyKey, file);
        return accepted(job);
    }

    /** 抽取课程标准 → 异步作业 202；结果含 draftId + 草稿。 */
    @PostMapping("/extract")
    public ResponseEntity<Result<AiJobVO>> extract(
            @RequestParam("courseCode") @NotBlank String courseCode,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        AiJobVO job = payloadSubmissionService.submitForCourseCode(
                courseCode, AiJobKind.GRAPH_EXTRACT, idempotencyKey, file);
        return accepted(job);
    }

    /** 按 draftId 拉取暂存草稿(供审核页刷新)。 */
    @GetMapping("/extract/{draftId}")
    public Result<GraphImportRequest> getDraft(@PathVariable @NotBlank String draftId) {
        return Result.ok(graphExtractReviewService.getDraft(draftId));
    }

    /** 提交审核结果:全量替换导入 + 返回指标。 */
    @Audited(action = "GRAPH_EXTRACT_COMMIT", target = "GRAPH")
    @PostMapping("/extract/{draftId}/commit")
    public Result<ExtractCommitResult> commit(@PathVariable @NotBlank String draftId,
                                              @Valid @RequestBody GraphImportRequest finalGraph) {
        return Result.ok(graphExtractReviewService.commit(draftId, finalGraph));
    }

    /** 抽取审核指标历史(倒序)。 */
    @GetMapping("/extract/reviews")
    public Result<List<ExtractionReview>> reviews(@RequestParam("courseCode") @NotBlank String courseCode) {
        return Result.ok(graphExtractReviewService.history(courseCode));
    }

    private static ResponseEntity<Result<AiJobVO>> accepted(AiJobVO job) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Result.ok(job));
    }
}
