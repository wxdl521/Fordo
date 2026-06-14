package com.wenjin.controller;

import com.wenjin.common.Result;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.dto.GraphImportResult;
import com.wenjin.service.GraphService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图谱管理接口（教师/管理侧）。本阶段不做权限，演示用。
 */
@RestController
@RequestMapping("/api/admin/graph")
public class GraphAdminController {

    private final GraphService graphService;

    public GraphAdminController(GraphService graphService) {
        this.graphService = graphService;
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
}
