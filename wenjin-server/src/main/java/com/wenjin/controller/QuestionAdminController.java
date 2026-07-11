package com.wenjin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.Result;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.AiJobVO;
import com.wenjin.dto.AnnotateRequest;
import com.wenjin.dto.ImportBankResult;
import com.wenjin.dto.QuestionBankFile;
import com.wenjin.service.AiJobService;
import com.wenjin.service.QuestionBankImportService;
import com.wenjin.support.AiJobKind;
import com.wenjin.support.AiJobPayloadStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * 题目管理接口（教师端）：AI 出题、存量题标注、题库文件导入。
 * <p>长写（generate / annotate / excel import）派发为可恢复 AI 作业，HTTP 202。
 */
@RestController
@RequestMapping("/api/admin/question")
@Validated
public class QuestionAdminController {

    private final QuestionBankImportService importService;
    private final ObjectMapper objectMapper;
    private final AiJobService aiJobService;
    private final AiJobPayloadStore payloadStore;

    public QuestionAdminController(QuestionBankImportService importService,
                                   ObjectMapper objectMapper,
                                   AiJobService aiJobService,
                                   AiJobPayloadStore payloadStore) {
        this.importService = importService;
        this.objectMapper = objectMapper;
        this.aiJobService = aiJobService;
        this.payloadStore = payloadStore;
    }

    /**
     * 为目标知识点 AI 出题（异步作业）。
     *
     * POST /api/admin/question/generate?courseId=1&nodeCode=KT07&count=5
     */
    @PostMapping("/generate")
    public ResponseEntity<Result<AiJobVO>> generate(
            @RequestParam("courseId") @Positive Long courseId,
            @RequestParam("nodeCode") @NotBlank String nodeCode,
            @RequestParam(value = "count", defaultValue = "5") @Min(1) @Max(20) int count,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("nodeCode", nodeCode);
        body.put("count", count);
        AiJobVO job = aiJobService.dispatch(courseId, AiJobKind.QUESTION_GENERATE, idempotencyKey, body);
        return accepted(job);
    }

    /**
     * 存量题标注（异步作业）。
     *
     * POST /api/admin/question/annotate?courseId=1
     */
    @PostMapping("/annotate")
    public ResponseEntity<Result<AiJobVO>> annotate(
            @RequestParam("courseId") @Positive Long courseId,
            @Valid @RequestBody AnnotateRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        AiJobVO job = aiJobService.dispatch(courseId, AiJobKind.QUESTION_ANNOTATE, idempotencyKey, req);
        return accepted(job);
    }

    /**
     * 从 JSON 文件导入题库（同步，无 AI）。
     *
     * POST /api/admin/question/import/json?courseId=1
     */
    @PostMapping("/import/json")
    public Result<ImportBankResult> importJson(@RequestParam("courseId") @Positive Long courseId,
                                               @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }
        QuestionBankFile bank;
        try {
            bank = objectMapper.readValue(file.getInputStream(), QuestionBankFile.class);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "JSON 文件解析失败：" + e.getMessage());
        }
        return Result.ok(importService.importFromJson(courseId, bank));
    }

    /**
     * 从 Excel 文件导入题库（含 AI 清洗，异步作业）。
     *
     * POST /api/admin/question/import/excel?courseId=1
     */
    @PostMapping("/import/excel")
    public ResponseEntity<Result<AiJobVO>> importExcel(
            @RequestParam("courseId") @Positive Long courseId,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Map<String, Object> meta = new HashMap<>(payloadStore.store(file));
        AiJobVO job = aiJobService.dispatch(courseId, AiJobKind.QUESTION_IMPORT_EXCEL, idempotencyKey, meta);
        return accepted(job);
    }

    private static ResponseEntity<Result<AiJobVO>> accepted(AiJobVO job) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Result.ok(job));
    }
}
