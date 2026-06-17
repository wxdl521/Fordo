package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.dto.GraphImportResult;
import com.wenjin.service.GraphImportService;
import com.wenjin.service.GraphService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图谱管理接口（教师/管理侧）。本阶段不做权限，演示用。
 */
@RestController
@RequestMapping("/api/admin/graph")
public class GraphAdminController {

    private final GraphService graphService;
    private final GraphImportService graphImportService;

    public GraphAdminController(GraphService graphService, GraphImportService graphImportService) {
        this.graphService = graphService;
        this.graphImportService = graphImportService;
    }

    /**
     * 导入图谱（同一课程重复导入 = 全量替换）。
     * 校验不通过时返回 code=1001 与逐条错误明细。
     *
     * POST /api/admin/graph/import?courseCode=52015CC4B4
     * body: 问津_软件工程图谱_v0.3.json 的内容（course + nodes + edges）
     */
    @PostMapping("/import")
    public Result<GraphImportResult> importGraph(@RequestParam("courseCode") String courseCode,
                                                 @RequestBody GraphImportRequest request) {
        return Result.ok(graphService.importGraph(courseCode, request));
    }

    /**
     * 从 Excel 文件导入图谱。
     * 解析 Excel（Sheet 1 节点 + Sheet 2 边）→ AI 清洗 → 导入。
     *
     * POST /api/admin/graph/import/excel?courseCode=52015CC4B4
     * body: multipart/form-data，字段名 file
     */
    @PostMapping("/import/excel")
    public Result<GraphImportResult> importGraphFromExcel(
            @RequestParam("courseCode") String courseCode,
            @RequestParam("file") MultipartFile file) {
        return Result.ok(graphImportService.importFromExcel(courseCode, file));
    }
}
