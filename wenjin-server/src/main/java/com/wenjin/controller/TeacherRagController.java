package com.wenjin.controller;

import com.wenjin.audit.Audited;
import com.wenjin.common.Result;
import com.wenjin.dto.BatchApproveMapsRequest;
import com.wenjin.dto.ChunkNodeMapPageVO;
import com.wenjin.dto.DocSourceVO;
import com.wenjin.dto.NodeRefIngestRequest;
import com.wenjin.service.rag.TeacherRagService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 教师端 RAG 语料审核 API（M8 UI）。
 * <p>{@code wenjin.features.rag-enabled=false} 时统一 404。
 */
@RestController
@RequestMapping("/api/teacher/rag")
@Validated
public class TeacherRagController {

    private final TeacherRagService teacherRagService;

    public TeacherRagController(TeacherRagService teacherRagService) {
        this.teacherRagService = teacherRagService;
    }

    /** GET /api/teacher/rag/sources?courseId= */
    @GetMapping("/sources")
    public Result<List<DocSourceVO>> listSources(@RequestParam @Positive Long courseId) {
        return Result.ok(teacherRagService.listSources(courseId));
    }

    /** GET /api/teacher/rag/mappings?courseId=&nodeCode=&status=&page=&size= */
    @GetMapping("/mappings")
    public Result<ChunkNodeMapPageVO> listMappings(
            @RequestParam @Positive Long courseId,
            @RequestParam(required = false) String nodeCode,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return Result.ok(teacherRagService.listMappings(courseId, nodeCode, status, page, size));
    }

    /** POST /api/teacher/rag/mappings/{id}/approve */
    @Audited(action = "RAG_MAPPING_APPROVE", target = "RAG")
    @PostMapping("/mappings/{id}/approve")
    public Result<Void> approve(@PathVariable("id") @Positive Long id) {
        teacherRagService.approve(id);
        return Result.ok(null);
    }

    /** POST /api/teacher/rag/mappings/{id}/reject */
    @Audited(action = "RAG_MAPPING_REJECT", target = "RAG")
    @PostMapping("/mappings/{id}/reject")
    public Result<Void> reject(@PathVariable("id") @Positive Long id) {
        teacherRagService.reject(id);
        return Result.ok(null);
    }

    /**
     * POST /api/teacher/rag/mappings/batch-approve?courseId=
     * body: { nodeCode?, minConfidence? }
     */
    @Audited(action = "RAG_MAPPING_BATCH_APPROVE", target = "RAG")
    @PostMapping("/mappings/batch-approve")
    public Result<Map<String, Integer>> batchApprove(
            @RequestParam @Positive Long courseId,
            @RequestBody(required = false) BatchApproveMapsRequest req
    ) {
        int affected = teacherRagService.batchApprove(courseId, req == null ? new BatchApproveMapsRequest() : req);
        return Result.ok(Map.of("affected", affected));
    }

    /** POST /api/teacher/rag/sources/node-ref body: { courseId, title?, markdown } */
    @PostMapping("/sources/node-ref")
    public Result<DocSourceVO> createNodeRef(@Valid @RequestBody NodeRefIngestRequest req) {
        return Result.ok(teacherRagService.createNodeRef(req));
    }
}
