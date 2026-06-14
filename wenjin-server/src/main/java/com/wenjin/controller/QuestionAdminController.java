package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.AnnotateItemResult;
import com.wenjin.dto.AnnotateRequest;
import com.wenjin.dto.GenerateResult;
import com.wenjin.dto.ImportBankResult;
import com.wenjin.service.QuestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 题目管理接口（教师/管理侧）：AI 出题、存量题标注、题库种子导入。本阶段不做权限，演示用。
 */
@RestController
@RequestMapping("/api/admin/question")
public class QuestionAdminController {

    private final QuestionService questionService;

    public QuestionAdminController(QuestionService questionService) {
        this.questionService = questionService;
    }

    /**
     * 为目标知识点 AI 出题（出题即标注，代码侧校验+去重后落库）。
     *
     * POST /api/admin/question/generate?nodeCode=KT07&count=5
     */
    @PostMapping("/generate")
    public Result<GenerateResult> generate(@RequestParam("nodeCode") String nodeCode,
                                           @RequestParam(value = "count", defaultValue = "5") int count) {
        return Result.ok(questionService.generate(nodeCode, count));
    }

    /**
     * 存量题标注（Prompt 3，超纲不强标），合法题落库。
     *
     * POST /api/admin/question/annotate
     * body: AnnotateRequest（items: [{stem, options}]）
     */
    @PostMapping("/annotate")
    public Result<List<AnnotateItemResult>> annotate(@RequestBody AnnotateRequest req) {
        return Result.ok(questionService.annotate(req));
    }

    /**
     * 题库种子导入（status=已通过, source=1），同课程题干已存在则跳过。
     *
     * POST /api/admin/question/import-bank?courseCode=52015CC4B4
     */
    @PostMapping("/import-bank")
    public Result<ImportBankResult> importBank(@RequestParam("courseCode") String courseCode) {
        return Result.ok(questionService.importBank(courseCode));
    }
}
