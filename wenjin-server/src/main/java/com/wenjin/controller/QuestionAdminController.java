package com.wenjin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenjin.common.BusinessException;
import com.wenjin.common.Result;
import com.wenjin.common.ResultCode;
import com.wenjin.dto.AnnotateItemResult;
import com.wenjin.dto.AnnotateRequest;
import com.wenjin.dto.GenerateResult;
import com.wenjin.dto.ImportBankResult;
import com.wenjin.dto.QuestionBankFile;
import com.wenjin.service.QuestionBankImportService;
import com.wenjin.service.QuestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 题目管理接口（教师端）：AI 出题、存量题标注、题库文件导入。
 */
@RestController
@RequestMapping("/api/admin/question")
public class QuestionAdminController {

    private final QuestionService questionService;
    private final QuestionBankImportService importService;
    private final ObjectMapper objectMapper;

    public QuestionAdminController(QuestionService questionService,
                                   QuestionBankImportService importService,
                                   ObjectMapper objectMapper) {
        this.questionService = questionService;
        this.importService = importService;
        this.objectMapper = objectMapper;
    }

    /**
     * 为目标知识点 AI 出题（出题即标注，代码侧校验+去重后落库）。
     *
     * POST /api/admin/question/generate?courseId=1&nodeCode=KT07&count=5
     */
    @PostMapping("/generate")
    public Result<GenerateResult> generate(@RequestParam("courseId") Long courseId,
                                           @RequestParam("nodeCode") String nodeCode,
                                           @RequestParam(value = "count", defaultValue = "5") int count) {
        return Result.ok(questionService.generate(courseId, nodeCode, count));
    }

    /**
     * 存量题标注（Prompt 3，超纲不强标），合法题落库。
     *
     * POST /api/admin/question/annotate?courseId=1
     * body: AnnotateRequest（items: [{stem, options}]）
     */
    @PostMapping("/annotate")
    public Result<List<AnnotateItemResult>> annotate(@RequestParam("courseId") Long courseId,
                                                     @RequestBody AnnotateRequest req) {
        return Result.ok(questionService.annotate(courseId, req));
    }

    /**
     * 从 JSON 文件导入题库（status=已通过, source=1），同课程题干已存在则跳过。
     *
     * POST /api/admin/question/import/json?courseId=1
     * form-data: file (JSON 文件)
     */
    @PostMapping("/import/json")
    public Result<ImportBankResult> importJson(@RequestParam("courseId") Long courseId,
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
     * 从 Excel 文件导入题库（含 AI 清洗），同课程题干已存在则跳过。
     *
     * POST /api/admin/question/import/excel?courseId=1
     * form-data: file (Excel 文件)
     */
    @PostMapping("/import/excel")
    public Result<ImportBankResult> importExcel(@RequestParam("courseId") Long courseId,
                                                @RequestParam("file") MultipartFile file) {
        return Result.ok(importService.importFromExcel(courseId, file));
    }
}
